public class ReflectTest {

    static class Shape {
        int width;
        int height;

        Shape(int w, int h) {
            this.width = w;
            this.height = h;
        }

        int area() {
            return this.width * this.height;
        }
    }

    public static void main(String[] args) {
        Shape s = new Shape(6, 7);
        Class<?> c = s.getClass();
        System.out.println(c.getName());
        System.out.println(c.getSimpleName());
        System.out.println(s.area());

        String txt = "hello";
        Class<?> sc = txt.getClass();
        System.out.println(sc.getName());
        System.out.println(sc.getSimpleName());

        System.out.println(c == c ? 1 : 0);
        System.out.println(c.getName().length());
    }
}