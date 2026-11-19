public class NumericTest {

    public static void main(String[] args) {
        double a = 1.5;
        double b = 2.25;
        System.out.println((int) ((a + b) * 10.0));
        System.out.println((int) ((b - a) * 100.0));
        System.out.println((int) ((a * b) * 10.0));
        System.out.println((int) ((b / a) * 100.0));

        long x = 1000000L;
        long y = 3L;
        System.out.println((int) ((x * y) / 1000L));
        System.out.println((int) (x % 7L));

        float f = 0.5f;
        System.out.println((int) ((f + 1.25f) * 4.0f));

        int i = 7;
        double d = i;
        System.out.println((int) (d * 3.0));
        double e = 9.75;
        System.out.println((int) e);
        System.out.println((long) e);
        System.out.println((int) ((double) i / 2.0 * 2.0));

        System.out.println(cmp(1.0, 2.0));
        System.out.println(cmp(2.0, 2.0));
        System.out.println(cmp(3.0, 2.0));
        System.out.println(lcmp(5L, 9L));

        System.out.println(area(3.0, 4.0));
    }

    static int cmp(double p, double q) {
        if (p < q) {
            return -1;
        }
        if (p > q) {
            return 1;
        }
        return 0;
    }

    static int lcmp(long p, long q) {
        if (p < q) {
            return 11;
        }
        return 22;
    }

    static int area(double w, double h) {
        double s = w * h;
        return (int) (s + 0.5);
    }
}