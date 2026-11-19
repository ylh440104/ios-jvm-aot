public class LambdaTest {

    interface IntOp {
        int apply(int a, int b);
    }

    interface Printer {
        void print(String s);
    }

    static int calc(IntOp op, int a, int b) {
        return op.apply(a, b);
    }

    public static void main(String[] args) {
        IntOp add = (a, b) -> a + b;
        IntOp mul = (a, b) -> a * b;
        System.out.println(add.apply(3, 4));
        System.out.println(mul.apply(3, 4));
        System.out.println(calc(add, 10, 20));

        final int base = 100;
        IntOp offset = (a, b) -> a + b + base;
        System.out.println(offset.apply(1, 2));

        Printer p = s -> System.out.println("out:" + s);
        p.print("hi");

        Runnable r = () -> System.out.println("ran");
        r.run();
    }
}