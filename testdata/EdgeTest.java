public class EdgeTest {

    public static void main(String[] args) {
        int min = -2147483648;
        System.out.println(min / -1);
        System.out.println(min % -1);
        System.out.println(7 / 2);
        System.out.println(-7 / 2);
        System.out.println(-7 % 2);

        System.out.println(1 << 32);
        System.out.println(1 << 33);
        System.out.println(-16 >> 2);
        System.out.println(-16 >>> 2);
        System.out.println(256 >>> 33);

        long lm = -9223372036854775808L;
        System.out.println(lm / -1L);
        System.out.println(1L << 64);
        System.out.println(1L << 65);
        System.out.println(-16L >> 2);
        System.out.println(256L >>> 65);

        System.out.println((int) 1.0E30);
        System.out.println((int) -1.0E30);
        System.out.println((int) 2147483648.0);
        System.out.println((int) -2147483649.0);
        System.out.println((long) 1.0E30);

        double nan = 0.0 / 0.0;
        System.out.println((int) nan);
        System.out.println((long) nan);
        float fnan = 0.0f / 0.0f;
        System.out.println((int) fnan);
    }
}