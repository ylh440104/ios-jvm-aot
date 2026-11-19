import java.lang.reflect.Field;

public class Reflect4 {

    static class Point {
        int x;

        Point(int x) {
            this.x = x;
        }
    }

    public static void main(String[] args) throws Exception {
        Point p = new Point(3);
        Class<?> c = p.getClass();
        Field fx = c.getDeclaredField("x");
        System.out.println(fx == null ? 0 : 1);
        System.out.println(fx.getName());
    }
}