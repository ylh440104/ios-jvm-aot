public class Hello {

    public static void main(String[] args) {
        System.out.println("hello from java 21 bytecode");
        int a = 17;
        int b = 25;
        System.out.println(add(a, b));
        System.out.println(sumLoop(10));
        System.out.println(Math.abs(-42));
    }

    static int add(int x, int y) {
        return x + y;
    }

    static int sumLoop(int n) {
        int total = 0;
        int i = 1;
        while (i <= n) {
            total = total + i * 2;
            i = i + 1;
        }
        return total;
    }
}