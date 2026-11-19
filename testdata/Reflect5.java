import java.lang.reflect.Field;

public class Reflect5 {

    static class Point {
        long y;

        Point(long y) {
            this.y = y;
        }
    }

    public static void main(String[] args) throws Exception {
        Point p = new Point(4);
        Class<?> c = p.getClass();
        Field fy = c.getDeclaredField("y");
        System.out.println(fy == null ? 0 : 1);
        Object got = fy.get(p);
        System.out.println(got == null ? 0 : 1);
        System.out.println(fy.get(p));
    }
}