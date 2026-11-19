public class ExcTest {

    public static void main(String[] args) {
        System.out.println(safeDiv(10, 2));
        System.out.println(safeDiv(10, 0));
        System.out.println(safeDiv(9, 3));

        System.out.println(thrower(0));
        System.out.println(thrower(1));
        System.out.println(thrower(2));

        System.out.println(chain(0));
        System.out.println(chain(1));
    }

    static int safeDiv(int a, int b) {
        try {
            if (b == 0) {
                throw new Boom(7);
            }
            return a / b;
        } catch (Boom e) {
            return -e.code;
        }
    }

    static int thrower(int mode) {
        try {
            if (mode == 0) {
                throw new Boom(11);
            }
            if (mode == 1) {
                throw new Other(22);
            }
            return 33;
        } catch (Other o) {
            return 100 + o.val;
        } catch (Boom e) {
            return 200 + e.code;
        }
    }

    static int chain(int mode) {
        try {
            return inner(mode);
        } catch (Boom e) {
            return 300 + e.code;
        }
    }

    static int inner(int mode) {
        if (mode == 0) {
            throw new Boom(5);
        }
        return 400;
    }

    static class Boom extends RuntimeException {
        int code;

        Boom(int c) {
            this.code = c;
        }
    }

    static class Other extends RuntimeException {
        int val;

        Other(int v) {
            this.val = v;
        }
    }
}