public class RangeTest {

    public static void main(String[] args) {
        System.out.println(twoBlocks(0));
        System.out.println(twoBlocks(1));
        System.out.println(guard(2));
        System.out.println(guardOut(0));
        System.out.println(outsideTry(1));
        System.out.println(nested(0));
        System.out.println(nested(1));
        System.out.println(finallyLike(0));
        System.out.println(finallyLike(1));
    }

    static int guard(int m) {
        try {
            return twoBlocks(m);
        } catch (Boom e) {
            return 900 + e.c;
        }
    }

    static int guardOut(int m) {
        try {
            return outsideTry(m);
        } catch (Boom e) {
            return 800 + e.c;
        }
    }

    static int twoBlocks(int m) {
        int r = 0;
        try {
            if (m == 0) {
                throw new Boom(1);
            }
            r = r + 10;
        } catch (Boom e) {
            r = r + 100;
        }
        try {
            if (m == 1) {
                throw new Boom(2);
            }
            r = r + 20;
        } catch (Boom e) {
            r = r + 200;
        }
        if (m == 2) {
            throw new Boom(3);
        }
        return r;
    }

    static int outsideTry(int m) {
        if (m == 0) {
            throw new Boom(9);
        }
        try {
            return 55;
        } catch (Boom e) {
            return 66;
        }
    }

    static int nested(int m) {
        try {
            try {
                if (m == 0) {
                    throw new Boom(4);
                }
                return 1;
            } catch (Boom e) {
                if (m == 1) {
                    throw new Boom(5);
                }
                return 2;
            }
        } catch (Boom e) {
            return 3;
        }
    }

    static int finallyLike(int m) {
        int v = 0;
        try {
            if (m == 0) {
                throw new Boom(6);
            }
            v = 7;
        } catch (Boom e) {
            v = 8;
        }
        return v;
    }

    static class Boom extends RuntimeException {
        int c;

        Boom(int x) {
            this.c = x;
        }
    }
}