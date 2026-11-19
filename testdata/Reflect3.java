import java.lang.reflect.Field;

public class Reflect3 {

    static class Point {
        int x;
        long y;

        Point(int x, long y) {
            this.x = x;
            this.y = y;
        }
    }

    public static void main(String[] args) throws Exception {
        Point p = new Point(3, 4);
        Class<?> c = p.getClass();

        Field fx = c.getDeclaredField("x");
        Field fy = c.getDeclaredField("y");
        System.out.println(fx.getName());
        System.out.println(fy.getName());

        System.out.println(fx.getInt(p));
        System.out.println(fy.get(p));

        fx.setInt(p, 30);
        System.out.println(fx.getInt(p));
        System.out.println(p.x);
    }
}