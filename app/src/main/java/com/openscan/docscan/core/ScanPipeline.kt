package com.openscan.docscan.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 扫描处理管线：把「原图 → 矫正 → 增强 → 放大」串成一条可复用的流程。
 *
 * 每一步都是可选开关，允许 UI 按用户选择动态组合，
 * 并且每一步都有独立降级策略，保证不会因为单步失败而整条链路中断。
 */
class ScanPipeline(private val context: Context) {

    init {
        // 手写分割是 object 单例（ScanEnhancer 无 Context），在此注入。
        HandwritingRemover.init(context)
    }

    private val superRes = SuperResolution(context)

    /** ML 文档分割器，首次用到时才加载模型（4.9MB，随 APK 打包）。 */
    private var segmenter: DocSegmenter? = null
    private fun segmenter(): DocSegmenter =
        segmenter ?: DocSegmenter(context).also { segmenter = it }

    /**
     * 内置分割模型是否不可用。
     *
     * 自用版模型已随包打包，正常情况**恒为 false**，第一级 ML 路径始终生效。
     * 保留这个变量是为了让"模型没打进包"这类打包失误能被一次性发现并降级
     * （而非每次检测都白读一遍 assets），自检页也会把它暴露出来。
     */
    private var modelMissing = false

    /** 文本种子检测器（纯 OpenCV，无状态可直接复用）。 */
    private var textSeed: TextSeedDetector? = null
    private fun textSeed(): TextSeedDetector =
        textSeed ?: TextSeedDetector().also { textSeed = it }

    /**
     * 离线 OCR 引擎，仅用于 90° 方向消歧。惰性创建（ML Kit 客户端初始化
     * 有成本，而只有页面被判横躺时才需要），release() 时统一释放。
     */
    private var ocr: OcrEngine? = null
    private fun ocrEngine(): OcrEngine =
        ocr ?: OcrEngine().also { ocr = it }

    /**
     * 输出分辨率档位。
     *
     * 用户诉求是"能选分辨率"，所以这里用**目标长边像素**表达，而不是
     * 模糊的"倍数" —— 前者是用户能理解、也能预期文件体积的量。
     * 实际放大由 [ScanPipeline.process] 决定用超分还是插值。
     */
    enum class Resolution(
        val label: String,
        val targetLongEdge: Int,
        val hint: String
    ) {
        /** 2000px：够看、体积小，适合文档归档。 */
        STANDARD("标准", 2000, "体积小"),

        /** 3000px：日常首选。 */
        HIGH("高清", 3000, "推荐"),

        /** 4000px：细节保留，接近原图。 */
        ULTRA("超清", 4000, "细节好"),

        /** 6000px：AI 超分放大，最清晰、最慢。 */
        MAX("极清", 6000, "最清晰·较慢");

        companion object {
            val DEFAULT = ULTRA
            val all: List<Resolution> get() = entries.toList()
        }
    }

    /** 处理参数。 */
    data class Options(
        val quad: DocumentGeometry.Quad? = null,
        val enhance: ScanEnhancer.Mode = ScanEnhancer.Mode.DE_SHADOW,
        /** 输出目标长边（px）。<= 0 表示保持矫正后的原始尺寸，不做任何放大。 */
        val targetLongEdge: Int = 0
    )

    data class Result(
        val bitmap: Bitmap,
        val appliedQuad: DocumentGeometry.Quad?,
        val usedNeuralUpscale: Boolean,
        /**
         * 降级记录：某一步失败时会在这里留下「步骤 → 原因」。
         *
         * 之前每一步失败都只写 Log 然后返回上一步结果，UI 完全感知不到，
         * 用户看到的就是「处理完了但和原图一模一样」，无从排查。
         * 现在把失败原因一路带回 UI 展示。
         */
        val warnings: List<String> = emptyList()
    )

    /**
     * 执行完整管线。必须在后台线程调用；内部已切到 [Dispatchers.Default]。
     *
     * @param onStage 阶段回调，用于 UI 展示进度文案
     */
    suspend fun process(
        source: Bitmap,
        options: Options,
        onStage: (Stage, Float) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.Default) {
        var current = source
        var quad = options.quad
        val warnings = mutableListOf<String>()

        // 0) 裁剪兜底：上游没给有效四边形（未检测完就点了下一步 / 检测失败 /
        //    还停在"全图默认框"）时，在这里自动重跑一次三级检测。
        //    三级串联约 100ms（同一张图有缓存，预览和保存只跑一次），
        //    比整页歪着输出好得多 —— 真机案例（v1.5.1）：拍屏幕上的斜报告，
        //    全图框进了增强页，输出整页歪 15°。
        if (quad == null || isNearlyFullFrame(quad, source.width, source.height)) {
            val recovered = runCatching { detectQuadCached(source) }.getOrNull()
            if (recovered != null && !isNearlyFullFrame(recovered, source.width, source.height)) {
                quad = recovered
            } else {
                // 检测不出（或只检出一个"全图框"）就明说，绝不能静默地把原图
                // 当"矫正结果"交给用户。真机案例（v1.5.2）：文本种子在白纸贴边
                // 的照片上长成全图，warp 等于没做，端正成了最后一道防线。
                warnings += "未检测到文档边缘，已按原图处理（建议返回裁剪页手动拖动角点）"
                quad = null
            }
        }

        // 1) 几何矫正（透视拉正）
        if (quad != null) {
            onStage(Stage.WARP, 0.05f)
            current = runCatching {
                // 矫正上限跟随用户选的目标分辨率，否则选"极清 6000"也会被
                // warpToRect 的默认上限 4000 先压一刀，再放大徒增模糊。
                DocumentGeometry.warpToRect(
                    current, quad,
                    maxEdge = maxOf(DocumentGeometry.DEFAULT_MAX_EDGE, options.targetLongEdge)
                )
            }.getOrElse { e ->
                Log.w(TAG, "透视矫正失败，保留原图", e)
                warnings += "透视矫正失败：${e.describe()}"
                current
            }
        }
        onStage(Stage.WARP, 0.3f)

        // 1.5) 自动端正：检测文字行倾角，倾斜 >= 0.3° 就转平。
        // 透视矫正只能拉正四边形框本身；手持拍书时框内文字行往往还歪着
        // 几度，直接影响阅读观感和 OCR 准确率（ML Kit 对倾斜文本明显变差）。
        // 无论有没有做透视矫正都跑 —— 没矫正的照片同样可能歪。
        val skewAngle = runCatching {
            DocumentGeometry.estimateSkewAngle(current)
        }.getOrElse { e ->
            Log.w(TAG, "倾斜检测失败，跳过自动端正", e)
            warnings += "倾斜检测失败：${e.describe()}"
            0.0
        }
        if (kotlin.math.abs(skewAngle) >= 0.3) {
            onStage(Stage.WARP, 0.32f)
            current = runCatching {
                DocumentGeometry.rotateToLevel(current, skewAngle)
            }.getOrElse { e ->
                Log.w(TAG, "自动端正失败，保留未旋转结果", e)
                warnings += "自动端正失败：${e.describe()}"
                current
            }
        }
        onStage(Stage.WARP, 0.35f)

        // 1.6) 90° 方向转正：竖直正向拍摄的页面被矫正成横躺（landscape）时，
        //      文字行是竖直的，±45° 倾斜估计的搜索窗根本够不到，输出"平躺"。
        //      用 0°/90° 双假设投影打分判定（倍率 > 1.3 判横躺）。
        //      打分只能证明"文字行是竖直的"，±90 两个旋转方向分数完全相同
        //      （一个正立一个倒立），恒取 +90；转反了（还差 180°）由步骤 2.7
        //      的通用 OCR 消歧兜底 —— 真机案例（v1.5.7）：横躺书页被判 90°
        //      但实际需要 90+180，只在这里消歧是不够的。
        val quarterTurn = runCatching {
            DocumentGeometry.estimateOrientation(current)
        }.getOrElse { e ->
            Log.w(TAG, "方向检测失败，跳过 90° 转正", e)
            0
        }
        if (quarterTurn == 90) {
            onStage(Stage.WARP, 0.36f)
            current = runCatching {
                DocumentGeometry.rotateToLevel(current, 90.0)
            }.getOrElse { e ->
                Log.w(TAG, "90° 转正失败，保留当前方向", e)
                warnings += "方向转正失败：${e.describe()}"
                current
            }
        }

        // 2) 增强（去阴影 / 二值化 / 灰度）
        // 2.0) 黑白模式：先放大到目标长边，再进二值化（v1.5.11 关键修复）。
        //      二值化是阈值判决 —— 在低分辨率上做，笔画只有 2~3px，Sauvola 窗口
        //      内混入大量背景像素，阈值噪声直接把笔画骨架切碎；事后再放大，
        //      断笔已经定型，救不回来（真机案例：影印书页在 1080×1920 原图里
        //      页面只有 1333×1920，二值化后整页文字斑驳离析）。
        //      先用 Lanczos 放大到输出分辨率，Sauvola 窗口、形态学核、笔画估宽
        //      全部在输出尺度上取值；步骤 3 检测到长边已达标会自动跳过。
        //      离线对拍（diag_blur2/3/4，tools/train/）：真机输入 1333×1920，
        //      旧顺序黑连通域 2.2 万，先放大后 1774，整页观感笔画实、断笔消失。
        //      去手写（REMOVE_INK）末端同为二值化，享受同款预放大。
        if ((options.enhance == ScanEnhancer.Mode.BLACK_WHITE ||
                options.enhance == ScanEnhancer.Mode.REMOVE_INK) && options.targetLongEdge > 0
        ) {
            val curLong = maxOf(current.width, current.height)
            if (options.targetLongEdge > curLong) {
                onStage(Stage.ENHANCE, 0.38f)
                current = runCatching {
                    DocumentGeometry.resizeLanczos(current, options.targetLongEdge)
                }.getOrElse { e ->
                    Log.w(TAG, "黑白预放大失败，按原分辨率二值化", e)
                    current
                }
            }
        }
        // 诊断转储（仅自用版、黑白模式）：写入应用私有目录（不进相册，
        // 不打扰用户），文件名固定每次覆盖，供微信外发需用文件管理器获取。
        val diagDump = options.enhance == ScanEnhancer.Mode.BLACK_WHITE && isSelfuseBuild()
        if (diagDump) runCatching { saveDiagImage(current, "input") }
        if (options.enhance != ScanEnhancer.Mode.ORIGINAL) {
            onStage(Stage.ENHANCE, 0.4f)
            current = runCatching {
                ScanEnhancer.enhance(current, options.enhance)
            }.getOrElse { e ->
                Log.w(TAG, "增强失败，保留上一步结果", e)
                warnings += "去阴影增强失败：${e.describe()}"
                current
            }
        }
        if (diagDump) runCatching { saveDiagImage(current, "output_bw") }
        onStage(Stage.ENHANCE, 0.65f)

        // 2.5) 二次端正（保险丝）：增强后的图像（黑白/灰度）里文字与背景
        //      泾渭分明，倾斜估计的可靠性远高于彩色原图。真机案例（v1.5.2）：
        //      拍屏幕上的斜报告，彩色原图上的第一次端正失效，输出整页斜 15°。
        //      在增强结果上再估一次，无论第一次为什么失效都能兜住。
        //      原图模式（ORIGINAL）内容没变，重跑只会得到同一个答案，跳过。
        if (options.enhance != ScanEnhancer.Mode.ORIGINAL) {
            val skew2 = runCatching {
                DocumentGeometry.estimateSkewAngle(current)
            }.getOrElse { e ->
                Log.w(TAG, "二次倾斜检测失败（忽略）", e)
                0.0
            }
            if (kotlin.math.abs(skew2) >= 0.3) {
                onStage(Stage.WARP, 0.66f)
                current = runCatching {
                    DocumentGeometry.rotateToLevel(current, skew2)
                }.getOrElse { e ->
                    Log.w(TAG, "二次端正失败，保留当前结果", e)
                    warnings += "自动端正失败：${e.describe()}"
                    current
                }
            }
        }

        // 2.7) 180° 方向消歧（通用兜底，所有模式生效）。
        //      投影类打分对 180° 完全不敏感 —— 行投影在旋转 180° 后只是
        //      顺序反转，统计量一模一样，倒立页面只能靠 OCR 判别。
        //      ★ v1.5.9：**无条件双向对比**。此前"正立 <20 字符才复核"的
        //      门在真机上永远进不去 —— ML Kit 对倒立中文照样检出"行结构"
        //      并返回大量乱码字符（远超 20），倒立页就这样漏网（影印书页
        //      三种模式全部颠倒、升级后依旧的真机案例）。
        //      现在：两个方向各识别一次探针图，倒立方向字符显著更多
        //      （1.2 倍 + ≥30 字符）才翻转。正立页两次 OCR 都跑（成本
        //      ~0.5s），换取消歧可靠性；倒立方向是乱码时字符数天然偏低。
        runCatching {
            val engine = ocrEngine()
            val probe = downscaleForOcrProbe(current)
            val upright = engine.recognizeOrEmpty(probe)
            if (probe !== current) probe.recycle()
            onStage(Stage.WARP, 0.68f)
            val flipped = DocumentGeometry.rotateToLevel(current, 180.0)
            val probe2 = downscaleForOcrProbe(flipped)
            val turned = engine.recognizeOrEmpty(probe2)
            if (probe2 !== flipped) probe2.recycle()
            if (turned.charCount > upright.charCount * ORI_FLIP_RATIO &&
                turned.charCount >= ORI_OCR_MIN_CHARS
            ) {
                Log.i(TAG, "OCR 消歧：页面倒立（${upright.charCount} vs ${turned.charCount} 字符），翻转 180°")
                if (current !== source) current.recycle()
                current = flipped
            } else {
                flipped.recycle()
            }
        }.getOrElse { e ->
            Log.w(TAG, "方向 OCR 消歧失败，保持当前方向", e)
        }

        // 3) 输出分辨率：按目标长边决定是否放大，以及用超分还是插值。
        //    超分模型只支持 2x / 4x 整数倍，比例接近时优先走它；
        //    其它比例用 Lanczos 插值（质量明显优于默认的双线性）。
        var usedNeural = false
        if (options.targetLongEdge > 0) {
            val curLong = maxOf(current.width, current.height)
            val ratio = options.targetLongEdge.toDouble() / curLong
            if (ratio > 1.05) {
                onStage(Stage.UPSCALE, 0.7f)
                val neuralScale = when {
                    ratio >= 3.5 -> 4
                    ratio >= 1.75 -> 2
                    else -> 0
                }
                if (neuralScale > 0) {
                    current = runCatching {
                        superRes.upscale(current, neuralScale) { p ->
                            onStage(Stage.UPSCALE, 0.7f + 0.28f * p)
                        }
                    }.getOrElse { e ->
                        Log.w(TAG, "超分放大失败，改用插值", e)
                        warnings += "高清放大失败：${e.describe()}"
                        current
                    }
                    usedNeural = superRes.activePath == SuperResolution.Path.NEURAL
                } else {
                    // 非整数比例：Lanczos 插值
                    current = runCatching {
                        DocumentGeometry.resizeLanczos(current, options.targetLongEdge)
                    }.getOrElse { e ->
                        Log.w(TAG, "分辨率调整失败，保留当前尺寸", e)
                        warnings += "分辨率调整失败：${e.describe()}"
                        current
                    }
                }
            }
        }

        onStage(Stage.DONE, 1f)
        Result(current, quad, usedNeural, warnings)
    }

    /**
     * 检测结果：四边形 + 过程诊断。
     *
     * 之前两级策略（ML → 边缘检测）内部的失败全被 runCatching 吞掉，
     * UI 只能笼统显示"未识别到文档边缘"，无法区分是 ML 没跑起来、
     * ML 判定画面里没有文档、还是边缘检测失败 —— 真机排查时完全两眼一抹黑。
     * 现在把每一级的结论带回给 UI 直接展示。
     */
    data class DetectOutcome(
        val quad: DocumentGeometry.Quad?,
        /** 人类可读的检测过程说明，恒不为空。 */
        val detail: String
    )

    /**
     * 只做边缘检测，供裁剪界面初始化使用。
     *
     * 三级策略：
     *   1. **ML 分割优先**（自用版内置 GPL 模型，始终生效）—— 按学习到的
     *      "文档外观"逐像素分类，对书页（弧形边、两页连通、文字淹没边缘）
     *      和复杂背景最鲁棒，实测 5/5 命中且边界最贴合；
     *   2. **文本种子 + 纸面生长** —— 纯 OpenCV：先找文字聚集区作种子，
     *      再沿"接近局部纸面亮度"的连通域长出整张纸。ML 未检出时的主力备份，
     *      对单页文档实测与 ML 基准 IoU 0.786、5/5 从不失败（详见 TextSeedDetector）；
     *   3. 传统边缘检测兜底 —— 干净场景下精度好。
     */
    suspend fun detectQuad(source: Bitmap): DetectOutcome =
        withContext(Dispatchers.Default) {
            // 第一级：ML 分割（模型未打包时直接跳过，不读 assets）。
            // 返回 null 有两种可能 —— 跑通了但画面里没有可信文档，或内部异常。
            val mlOutcome = if (modelMissing) {
                null to "ML 模型未打包或加载失败，改用文本种子检测"
            } else {
                runCatching {
                    val seg = segmenter()
                    val q = seg.detectQuad(source)
                    if (seg.modelMissing) modelMissing = true
                    val detail = if (q != null) {
                        "ML 分割命中"
                    } else {
                        "ML 分割未检出：${seg.lastError ?: "未知原因"}"
                    }
                    q to detail
                }.getOrElse { e ->
                    null to "ML 分割异常：${e.describe()}"
                }
            }
            if (mlOutcome.first != null) {
                return@withContext DetectOutcome(mlOutcome.first, mlOutcome.second)
            }

            // 第二级：文本种子 + 纸面生长（ML 未检出时的主力备份）。
            // 三方实测（tools/train/compare3way.py，以 ML 为基准）：
            // 文本种子平均 IoU 0.786 / 5 张全中，远优于重训模型的 0.523 / 4 张。
            val seedOutcome = runCatching {
                val det = textSeed()
                val q = det.detectQuad(source)
                q to (if (q != null) "文本种子检测命中" else "文本种子未检出：${det.lastError ?: "未知原因"}")
            }.getOrElse { e ->
                null to "文本种子检测异常：${e.describe()}"
            }
            if (seedOutcome.first != null) {
                return@withContext DetectOutcome(seedOutcome.first, seedOutcome.second)
            }

            // 第三级：传统边缘检测兜底。
            val edgeOutcome = runCatching {
                val q = DocumentGeometry.detectDocumentQuad(source)
                q to (if (q != null) "边缘检测命中" else "边缘检测未检出")
            }.getOrElse { e ->
                null to "边缘检测异常：${e.describe()}"
            }
            if (edgeOutcome.first != null) {
                return@withContext DetectOutcome(edgeOutcome.first, edgeOutcome.second)
            }
            DetectOutcome(null, "${seedOutcome.second}；${edgeOutcome.second}")
        }

    /** [detectQuadCached] 的缓存键：按源图对象身份缓存，预览与保存共用一次检测。 */
    private var cachedRecoverySource: Bitmap? = null
    private var cachedRecoveryQuad: DocumentGeometry.Quad? = null

    /**
     * 带缓存的检测兜底：同一张源图只跑一次三级检测。
     *
     * 预览（每次切滤镜都会走 process）和"加入文档"（全分辨率重跑）共用，
     * 避免同一张图重复做 ML 推理。位图由调用方持有并回收，这里只存引用做
     * 身份比较，不负责释放。
     */
    private suspend fun detectQuadCached(source: Bitmap): DocumentGeometry.Quad? {
        if (source === cachedRecoverySource) return cachedRecoveryQuad
        val q = detectQuad(source).quad
        cachedRecoverySource = source
        cachedRecoveryQuad = q
        return q
    }

    /**
     * 判断四边形是否几乎就是整幅画面 —— 裁剪页在检测完成/失败前给的
     * "全图默认框"（四角内缩 2%）就是这个形态，拿它做透视变换只会把
     * 四边再内缩一点，没有矫正意义。
     */
    private fun isNearlyFullFrame(q: DocumentGeometry.Quad, w: Int, h: Int): Boolean {
        val m = 0.03 * minOf(w, h)
        return q.tl.x <= m && q.tl.y <= m &&
            q.tr.x >= w - m && q.tr.y <= m &&
            q.br.x >= w - m && q.br.y >= h - m &&
            q.bl.x <= m && q.bl.y >= h - m
    }

    /**
     * 加载图片并按需降采样，避免超大图直接 OOM。
     *
     * ★ v1.5.9：**必须应用 EXIF 方向**。手机竖拍的照片原始像素几乎都是
     * 横的（传感器横向安装），「竖直」只存在 EXIF 标签里 —— 相册/微信
     * 等查看器都会自动应用，BitmapFactory 不会。此前相册导入完全忽略
     * EXIF，管线拿到的是横躺/颠倒的原始像素：90° 分支恒转 +90（±90
     * 投影分数相同）又是一半概率转反 —— 这就是"方向颠倒"问题的根源
     * （真机 v1.5.9，影印书页三种模式全部上下颠倒）。
     */
    suspend fun loadBitmap(uri: Uri, maxEdge: Int = LOAD_MAX_EDGE): Bitmap =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw IllegalStateException("无法读取图片：$uri")
            val exifOrientation = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            return@withContext applyExifOrientation(decoded, exifOrientation)
        }

    /** 从本地文件加载（相机拍照结果）。 */
    suspend fun loadBitmap(file: File, maxEdge: Int = LOAD_MAX_EDGE): Bitmap =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                ?: throw IllegalStateException("无法解码文件：${file.absolutePath}")
            val exifOrientation = runCatching {
                androidx.exifinterface.media.ExifInterface(file.absolutePath).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            return@withContext applyExifOrientation(decoded, exifOrientation)
        }

    /**
     * 按 EXIF 方向旋转载入的位图。镜像类方向（TRANSPOSE/TRANSVERSE，拍摄场景
     * 几乎不会出现）按对应角度近似处理。
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** 写入 JPEG。quality 默认 92，在体积与质量间取平衡。 */
    suspend fun saveJpeg(bitmap: Bitmap, target: File, quality: Int = 92) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                fos.flush()
            }
            target
        }

    fun release() {
        superRes.close()
        segmenter?.release()
        ocr?.close()
    }

    /** 90° 消歧用 OCR 探针图：长边压到 [ORI_PROBE_EDGE]，识别"有没有字"足够了。 */
    private fun downscaleForOcrProbe(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= ORI_PROBE_EDGE) return src
        val k = ORI_PROBE_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            maxOf((src.width * k).toInt(), 1),
            maxOf((src.height * k).toInt(), 1),
            true
        )
    }

    /** 自用版构建判定（诊断转储只在自用版开启）。 */
    private fun isSelfuseBuild(): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName?.contains("selfuse") == true
    }.getOrDefault(false)

    /**
     * 诊断转储：把位图写入**应用私有**外部目录 `Android/data/<pkg>/files/diag/`。
     * ★ v1.5.11：此前写入相册「Pictures/轻松扫描」导致每次扫描相册多出两张
     * 诊断图，用户误以为产生垃圾文件 —— 改私有目录后相册完全不可见，且
     * 文件名固定（diag_input.jpg / diag_output_bw.jpg）每次覆盖不堆积。
     * 取用方式：adb pull 或文件管理器进入 Android/data。仅自用版黑白模式调用。
     */
    private fun saveDiagImage(bitmap: Bitmap, tag: String) {
        val dir = context.getExternalFilesDir("diag") ?: return
        val target = File(dir, "diag_$tag.jpg")
        FileOutputStream(target).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge) {
            sample *= 2
        }
        return sample
    }

    enum class Stage { WARP, ENHANCE, UPSCALE, DONE }

    companion object {
        private const val TAG = "ScanPipeline"

        /** 载入时限制长边，防止 5000 万像素图直接崩掉。 */
        const val LOAD_MAX_EDGE = 3200

        /** 方向消歧：倒立方向字符超过正立 1.2 倍且 ≥30 字符才翻转。 */
        private const val ORI_FLIP_RATIO = 1.2

        /** 方向消歧：翻转方向至少要识别出这么多字符才可信。 */
        private const val ORI_OCR_MIN_CHARS = 30

        /** 方向消歧探针图长边上限。 */
        private const val ORI_PROBE_EDGE = 2000
    }
}

/**
 * 把异常转成一句可读的话。
 *
 * 不能用 e.message 直接拼：像 UnsatisfiedLinkError（OpenCV 原生库没加载上来）
 * 这类错误的 message 经常是 null，或者只有一行 "No implementation found for ..."，
 * 直接显示会是"失败：null"，反而掩盖了真正原因。
 *
 * 声明为 internal 是为了让 SelfCheck 复用同一套描述逻辑。
 */
internal fun Throwable.describe(): String {
    val msg = message?.takeIf { it.isNotBlank() }
    return msg ?: "${this::class.java.simpleName}（无详细信息）"
}
