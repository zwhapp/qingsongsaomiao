package inkcheck

import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** 逐阶段对拍 binarizeBlackWhite：与 Python 端 diag_bin_hyst 的中间量对比。 */
fun main() {
    OpenCV.loadLocally()
    val path = "../../tools/train/_bin_v2/gray_screen.png"
    val gray = Imgcodecs.imread(path, Imgcodecs.IMREAD_GRAYSCALE)
    require(!gray.empty()) { "cannot read $path" }
    val g32 = Mat()
    gray.convertTo(g32, org.opencv.core.CvType.CV_32F)

    val longest = maxOf(gray.cols(), gray.rows())
    var win = (longest / 65).coerceIn(15, 201)
    if (win % 2 == 0) win += 1
    println("win=$win  size=${gray.cols()}x${gray.rows()}")

    val mean = Mat()
    val sq = Mat()
    val meanSq = Mat()
    val std = Mat()
    Imgproc.boxFilter(g32, mean, org.opencv.core.CvType.CV_32F, Size(win.toDouble(), win.toDouble()))
    Core.multiply(g32, g32, sq)
    Imgproc.boxFilter(sq, meanSq, org.opencv.core.CvType.CV_32F, Size(win.toDouble(), win.toDouble()))
    Core.multiply(mean, mean, sq)
    Core.subtract(meanSq, sq, std)
    Core.max(std, Scalar(0.0), std)
    val sqrtStd = Mat()
    Core.sqrt(std, sqrtStd)
    sqrtStd.copyTo(std)
    sqrtStd.release()

    val tStrong = Mat()
    val tWeak = Mat()
    val t2 = Mat()
    val kS = 0.34; val kW = 0.18; val R = 128.0
    Core.multiply(mean, std, t2, kS / R)
    Core.multiply(mean, Scalar(1.0 - kS), tStrong)
    Core.add(tStrong, t2, tStrong)
    Core.multiply(mean, std, t2, kW / R)
    Core.multiply(mean, Scalar(1.0 - kW), tWeak)
    Core.add(tWeak, t2, tWeak)
    t2.release()

    val strong = Mat()
    val weak = Mat()
    Core.compare(g32, tStrong, strong, Core.CMP_LT)
    Core.compare(g32, tWeak, weak, Core.CMP_LT)

    fun pct(m: Mat) = (m.total() - Core.countNonZero(m)).toDouble() / m.total() * 100.0
    // strong/weak 里 255=墨；countNonZero 数的是墨本身
    println("mean.avg=%.2f  std.avg=%.2f".format(Core.mean(mean).`val`[0], Core.mean(std).`val`[0]))
    println("T_strong.avg=%.2f  T_weak.avg=%.2f".format(Core.mean(tStrong).`val`[0], Core.mean(tWeak).`val`[0]))
    println("strong(墨)占比=%.2f%%  weak(墨)占比=%.2f%%".format(
        Core.countNonZero(strong).toDouble() / strong.total() * 100.0,
        Core.countNonZero(weak).toDouble() / weak.total() * 100.0))
}
