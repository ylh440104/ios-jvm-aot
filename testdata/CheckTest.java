public class CheckTest {

    public static void main(String[] args) {
        System.out.println(oob(3));
        System.out.println(oob(-1));
        System.out.println(npe(0));
        System.out.println(npe(1));
        System.out.println(bytes());
        System.out.println(chars());
        System.out.println(neg(2));
        System.out.println(neg(-2));
        System.out.println(lenNull(0));
        System.out.println(lenNull(1));
    }

    static int oob(int i) {
        int[] a = new int[3];
        try {
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -100;
        }
    }

    static int npe(int m) {
        Node n = null;
        if (m == 1) {
            n = new Node();
        }
        try {
            return n.v;
        } catch (NullPointerException e) {
            return -200;
        }
    }

    static int bytes() {
        byte[] b = new byte[4];
        b[0] = 100;
        b[1] = -100;
        b[2] = 127;
        b[3] = -128;
        return b[0] + b[1] + b[2] + b[3] + b.length;
    }

    static int chars() {
        char[] c = new char[3];
        c[0] = 65;
        c[1] = 65535;
        c[2] = 0;
        return c[0] + c[1] + c[2] + c.length;
    }

    static int neg(int n) {
        try {
            int[] a = new int[n];
            return a.length;
        } catch (NegativeArraySizeException e) {
            return -300;
        }
    }

    static int lenNull(int m) {
        int[] a = null;
        if (m == 1) {
            a = new int[5];
        }
        try {
            return a.length;
        } catch (NullPointerException e) {
            return -400;
        }
    }

    static class Node {
        int v;

        Node() {
            this.v = 42;
        }
    }
}