package com.openscan.docscan.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openscan.docscan.ui.screen.CameraScreen
import com.openscan.docscan.ui.screen.DocumentListScreen
import com.openscan.docscan.ui.screen.EnhanceScreen
import com.openscan.docscan.ui.screen.HomeScreen
import com.openscan.docscan.ui.screen.LicensesScreen
import com.openscan.docscan.ui.screen.PageManagerScreen
import com.openscan.docscan.ui.screen.ReviewScreen
import com.openscan.docscan.ui.screen.ScanSession
import com.openscan.docscan.ui.screen.SelfCheckScreen
import com.openscan.docscan.ui.screen.SponsorScreen

/** 路由表。 */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"           // 拍照（可反复进入，支持连拍）
    const val REVIEW = "review"           // 裁剪矫正
    const val ENHANCE = "enhance"         // 增强与放大
    const val PAGES = "pages"             // 多页管理 + 保存
    const val DOCUMENTS = "documents"
    const val DOC_DETAIL = "doc/{docId}"
    const val SELF_CHECK = "selfcheck"    // 环境自检（排查用）
    const val LICENSES = "licenses"       // 开源许可说明（合规要求）
    const val SPONSOR = "sponsor"         // 赞助开发者（仅分发版入口）

    fun docDetail(id: Long) = "doc/$id"
}

/**
 * 导航图。
 *
 * ## 多页扫描的状态机
 *
 * ```
 * HOME ──拍照──▶ CAMERA ──▶ REVIEW ──▶ ENHANCE ──▶ PAGES
 *                  ▲                                  │
 *                  └────────────「继续拍」─────────────┘
 *                                                     │
 *                                        「完成保存」──▶ DOC_DETAIL
 * ```
 *
 * [Routes.PAGES] 是收口环节：所有已确认的页都在那里排队，
 * 用户可以排序、删除，再决定保存或回去补拍。
 *
 * 从首页「导入图片」直接进 [Routes.REVIEW]，走的是同一条链路。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: (onGranted: () -> Unit) -> Unit,
    onPermissionDenied: () -> Unit,
    permissionDeniedHint: Boolean,
    dismissPermissionHint: () -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onCapture = {
                    requestCameraPermission {
                        if (hasCameraPermission()) {
                            // 新的一次扫描：清掉上一轮的残留草稿
                            ScanSession.start()
                            navController.navigate(Routes.CAMERA)
                        } else {
                            onPermissionDenied()
                        }
                    }
                },
                onImport = {
                    ScanSession.start()
                    navController.navigate(Routes.REVIEW)
                },
                onOpenDocuments = { navController.navigate(Routes.DOCUMENTS) },
                onOpenSelfCheck = { navController.navigate(Routes.SELF_CHECK) },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                onOpenSponsor = { navController.navigate(Routes.SPONSOR) },
                permissionDeniedHint = permissionDeniedHint,
                dismissPermissionHint = dismissPermissionHint
            )
        }

        composable(Routes.SELF_CHECK) {
            SelfCheckScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LICENSES) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SPONSOR) {
            SponsorScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CAMERA) {
            CameraScreen(
                onBack = { navController.popBackStack() },
                // 连拍模式：拍完所有页点「完成拍摄」→ 页面管理页统一识别
                onFinish = { navController.navigate(Routes.PAGES) },
                // 已拍页数角标：直接跳到页面管理
                onOpenPages = {
                    navController.navigate(Routes.PAGES)
                }
            )
        }

        composable(Routes.REVIEW) {
            ReviewScreen(
                onBack = {
                    // 从拍照进来的，返回时回到相机；从导入进来的，回首页
                    if (navController.previousBackStackEntry?.destination?.route
                        == Routes.CAMERA
                    ) {
                        navController.popBackStack()
                    } else {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                },
                onNext = { navController.navigate(Routes.ENHANCE) }
            )
        }

        composable(Routes.ENHANCE) {
            EnhanceScreen(
                onBack = { navController.popBackStack() },
                onAdded = {
                    // 一页处理完毕 → 去页面管理。把扫描中间页全部弹出，
                    // 栈收敛为 HOME→PAGES。
                    // ★ 必须用 HOME 收口而不是 CAMERA：导入流程（HOME→REVIEW→
                    //   ENHANCE）栈里根本没有 CAMERA，popUpTo(CAMERA) 找不到
                    //   目标就什么都不弹，REVIEW/ENHANCE 留在栈里——之后从
                    //   PAGES 一路按返回会退回这两页，而 reviewSource 已被
                    //   回收，看到的就是"黑屏的调整边缘页"（真机案例）。
                    navController.navigate(Routes.PAGES) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.PAGES) {
            PageManagerScreen(
                onBack = { navController.popBackStack() },
                onAddMore = {
                    // 回到相机继续拍。拍照流程直接弹回栈里的 CAMERA；
                    // 导入流程栈里没有 CAMERA（HOME→PAGES），navigate 补一个。
                    // 这样无论哪条路径进来，"继续拍→拍完→加入"之后栈都收敛
                    // 为 HOME→PAGES，不会堆积旧页面。
                    if (!navController.popBackStack(Routes.CAMERA, inclusive = false)) {
                        navController.navigate(Routes.CAMERA)
                    }
                },
                onSaved = { docId ->
                    if (docId < 0) {
                        // 用户放弃本次扫描
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    } else {
                        navController.navigate(Routes.docDetail(docId)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                }
            )
        }

        composable(Routes.DOCUMENTS) {
            DocumentListScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.docDetail(id)) }
            )
        }

        composable(
            route = Routes.DOC_DETAIL,
            arguments = listOf(navArgument("docId") { type = NavType.LongType })
        ) { entry ->
            val docId = entry.arguments?.getLong("docId") ?: 0L
            DocumentListScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.docDetail(id)) },
                initialDocId = docId,
                detailMode = true
            )
        }
    }
}
