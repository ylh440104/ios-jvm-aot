public class IndyTest {

    static class Point {
        int x;
        int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public String toString() {
            return "(" + this.x + "," + this.y + ")";
        }
    }

    public static void main(String[] args) {
        int i = 42;
        long l = 1234567890123L;
        double d = 2.5;
        char c = 'Z';
        boolean b = true;
        String s = "abc";

        System.out.println("int=" + i);
        System.out.println("long=" + l);
        System.out.println("double=" + d);
        System.out.println("char=" + c);
        System.out.println("bool=" + b);
        System.out.println("str=" + s);
        System.out.println("mix " + i + "/" + l + "/" + d);
        System.out.println(i + "+" + i + "=" + (i + i));

        Point p = new Point(3, 7);
        System.out.println("point " + p);

        String empty = "";
        System.out.println("pre" + empty + "post");
        System.out.println("neg=" + (-17));

        StringBuilder sb = new StringBuilder();
        sb.append("sb" + i);
        System.out.println(sb.toString());
    }
}