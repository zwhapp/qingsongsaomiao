import java.lang.reflect.Array;

public class ShapeLogic {
    static int computeNumDimensions(Object o) {
        if (o == null || !o.getClass().isArray()) return 0;
        if (Array.getLength(o) == 0) throw new IllegalArgumentException("Array lengths cannot be 0.");
        return 1 + computeNumDimensions(Array.get(o, 0));
    }
    static void fillShape(Object o, int dim, int[] shape) {
        if (shape == null || dim == shape.length) return;
        int len = Array.getLength(o);
        if (shape[dim] == 0) shape[dim] = len;
        else if (shape[dim] != len)
            throw new IllegalArgumentException("Mismatched lengths (" + shape[dim] + " and " + len + ") in dimension " + dim);
        if (Array.getLength(o) > 0) fillShape(Array.get(o, 0), dim + 1, shape);
    }
    public static void main(String[] a) {
        Object three = new float[1][256][1];
        Object four  = new float[1][256][256][1];
        System.out.println("三维 [1][256][1]      -> numDimensions=" + computeNumDimensions(three));
        System.out.println("四维 [1][256][256][1] -> numDimensions=" + computeNumDimensions(four));
        int[] shapeThree = {1,256,1};
        int[] shapeFour = {1,256,256,1};
        for (int[] s : new int[][]{shapeThree, shapeFour}) {
            for (String label : new String[]{"3d","4d"}) {
                Object o = label.equals("3d") ? three : four;
                int[] probe = new int[s.length];
                try {
                    fillShape(o, 0, probe);
                    System.out.println("  对象" + label + " vs 张量形状" + java.util.Arrays.toString(s) + " -> fillShape 结果 " + java.util.Arrays.toString(probe));
                } catch (Throwable t) {
                    System.out.println("  对象" + label + " vs 张量形状" + java.util.Arrays.toString(s) + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
    }
}
