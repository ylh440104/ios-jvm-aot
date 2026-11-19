import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Reflect10 {

    static class Point {
        int x;
        int y;
        String label;

        Point() {
            this.x = 0;
            this.y = 0;
            this.label = "origin";
        }

        Point(int x, int y) {
            this.x = x;
            this.y = y;
            this.label = "pt";
        }

        int sum() {
            return this.x + this.y;
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> k = Class.forName("Reflect10$Point");

        Constructor<?> c0 = k.getDeclaredConstructor(new Class<?>[0]);
        System.out.println(c0 == null ? 0 : 1);
        Object o0 = c0.newInstance(new Object[0]);
        Point p0 = (Point) o0;
        System.out.println(p0.sum());
        System.out.println(p0.label);

        Class<?>[] sig = new Class<?>[] { int.class, int.class };
        Constructor<?> c1 = k.getDeclaredConstructor(sig);
        Object o1 = c1.newInstance(new Object[] { 5, 7 });
        Point p1 = (Point) o1;
        System.out.println(p1.sum());

        Field[] fs = k.getDeclaredFields();
        System.out.println(fs.length);
        for (int i = 0; i < fs.length; i++) {
            System.out.println(fs[i].getName());
        }

        Method[] ms = k.getDeclaredMethods();
        System.out.println(ms.length);
        for (int i = 0; i < ms.length; i++) {
            System.out.println(ms[i].getName());
        }
    }
}