public class Bench1 {

    static long loopSum(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += i % 7;
        }
        return s;
    }

    static long fib(int n) {
        if (n < 2) {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }

    static int[] sortData(int n) {
        int[] a = new int[n];
        long x = 12345;
        for (int i = 0; i < n; i++) {
            x = (x * 6364136223846793005L + 1442695040888963407L) >>> 11;
            a[i] = (int) (x & 0xFFFF);
        }
        return a;
    }

    static void quickSort(int[] a, int lo, int hi) {
        int i = lo;
        int j = hi;
        int p = a[(lo + hi) >>> 1];
        while (i <= j) {
            while (a[i] < p) {
                i++;
            }
            while (a[j] > p) {
                j--;
            }
            if (i <= j) {
                int t = a[i];
                a[i] = a[j];
                a[j] = t;
                i++;
                j--;
            }
        }
        if (lo < j) {
            quickSort(a, lo, j);
        }
        if (i < hi) {
            quickSort(a, i, hi);
        }
    }

    static long objWork(int n) {
        long acc = 0;
        for (int i = 0; i < n; i++) {
            Integer a = i;
            Integer b = i + 1;
            acc += a.intValue() + b.intValue();
        }
        return acc;
    }

    public static void main(String[] args) {
        int rounds = 3;

        long t0 = System.nanoTime();
        long r1 = 0;
        for (int i = 0; i < rounds; i++) {
            r1 = loopSum(20000000);
        }
        long t1 = System.nanoTime();

        long t2 = System.nanoTime();
        long r2 = 0;
        for (int i = 0; i < rounds; i++) {
            r2 = fib(32);
        }
        long t3 = System.nanoTime();

        long[] tSort = new long[rounds];
        long r3 = 0;
        for (int i = 0; i < rounds; i++) {
            int[] arr = sortData(2000000);
            long s0 = System.nanoTime();
            quickSort(arr, 0, arr.length - 1);
            tSort[i] = System.nanoTime() - s0;
            r3 += arr[0] + arr[arr.length - 1];
        }

        long t4 = System.nanoTime();
        long r4 = 0;
        for (int i = 0; i < rounds; i++) {
            r4 = objWork(3000000);
        }
        long t5 = System.nanoTime();

        long s1 = (t1 - t0) / rounds / 1000;
        long s2 = (t3 - t2) / rounds / 1000;
        long s3 = (tSort[0] + tSort[1] + tSort[2]) / rounds / 1000;
        long s4 = (t5 - t4) / rounds / 1000;

        System.out.println("loop_us=" + s1);
        System.out.println("fib_us=" + s2);
        System.out.println("sort_us=" + s3);
        System.out.println("obj_us=" + s4);
        System.out.println("sum=" + (r1 + r2 + r3 + r4));
    }
}