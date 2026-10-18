import org.tensorflow.lite.Interpreter;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * 在桌面 JVM 上直跑真机用的同一个 tflite 模型与同一段输出缓冲代码，
 * 用来判定「真机 ML 不生效」到底是不是输出缓冲维度导致的。
 *
 * 这一段完全复刻 DocSegmenter.detectQuad 的推理部分（含修复后的四维写法
 * 与修复前的三维写法），两种写法都跑一遍，看哪个抛异常。
 */
public class TfliteShapeProbe {

    static Interpreter load(String path) throws Exception {
        File f = new File(path);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileChannel ch = raf.getChannel()) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
            return new Interpreter(m);
        }
    }

    public static void main(String[] args) throws Exception {
        String model = args[0];
        int N = 256;
        Interpreter eng = load(model);

        System.out.println("input  count = " + eng.getInputTensorCount());
        System.out.println("output count = " + eng.getOutputTensorCount());
        System.out.println("input  shape = " + Arrays.toString(eng.getInputTensor(0).shape()));
        System.out.println("output shape = " + Arrays.toString(eng.getOutputTensor(0).shape()));
        System.out.println("input  dtype = " + eng.getInputTensor(0).dataType());
        System.out.println("output dtype = " + eng.getOutputTensor(0).dataType());

        // 造一个确定性的输入：左半黑右半白
        ByteBuffer input = ByteBuffer.allocateDirect(1 * N * N * 3 * 4)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float v = (x < N / 2) ? 0.05f : 0.95f;
                input.putFloat(v); input.putFloat(v); input.putFloat(v);
            }
        }
        input.rewind();

        // --- 写法 A：修复前（三维） ---
        System.out.println("\n=== A) 三维 [1][256][1]（修复前的写法） ===");
        try {
            Object outA = new float[1][N][1];
            eng.run(input.duplicate(), outA);
            System.out.println("  A 未抛异常（意外）");
        } catch (Throwable t) {
            System.out.println("  A 抛异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // --- 写法 B：修复后（四维） ---
        System.out.println("\n=== B) 四维 [1][256][256][1]（修复后的写法） ===");
        try {
            float[][][][] outB = new float[1][N][N][1];
            eng.run(input.duplicate(), outB);
            System.out.println("  B 未抛异常 —— 推理成功");
            // 统计前景概率分布，确认推理结果非平凡
            int hot = 0;
            float max = -Float.MAX_VALUE, min = Float.MAX_VALUE;
            double sum = 0;
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++) {
                    float lg = outB[0][y][x][0];
                    if (lg > max) max = lg;
                    if (lg < min) min = lg;
                    sum += lg;
                    if (lg > 0) hot++;
                }
            System.out.printf("  logits: min=%.3f max=%.3f mean=%.4f%n", min, max, sum / (N * N));
            System.out.printf("  正 logits 占比 = %.1f%%（若约 50%% 说明模型确实在分割左右两半）%n",
                    100.0 * hot / (N * N));
        } catch (Throwable t) {
            System.out.println("  B 抛异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // --- 写法 C：官方推荐的一维扁平 float[N]（对照） ---
        System.out.println("\n=== C) 官方扁平 float[256*256]（对照组） ===");
        try {
            float[] outC = new float[N * N];
            eng.run(input.duplicate(), outC);
            System.out.println("  C 未抛异常 —— 推理成功");
            int hot = 0;
            for (float v : outC) if (v > 0) hot++;
            System.out.printf("  正 logits 占比 = %.1f%%%n", 100.0 * hot / (N * N));
        } catch (Throwable t) {
            System.out.println("  C 抛异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        eng.close();
    }
}
