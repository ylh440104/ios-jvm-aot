public class ObjTest {

    public static void main(String[] args) {
        Point p = new Point(3, 4);
        System.out.println(p.sum());
        p.x = 10;
        System.out.println(p.sum());

        Shape s = new Square(5);
        System.out.println(s.area());
        Shape t = new Rect(3, 7);
        System.out.println(t.area());
        System.out.println(s.name());
    }

    static class Point {
        int x;
        int y;

        Point(int a, int b) {
            this.x = a;
            this.y = b;
        }

        int sum() {
            return this.x + this.y;
        }
    }

    static class Shape {
        int size;

        Shape(int z) {
            this.size = z;
        }

        int area() {
            return 0;
        }

        int name() {
            return 1;
        }
    }

    static class Square extends Shape {
        Square(int z) {
            super(z);
        }

        int area() {
            return this.size * this.size;
        }
    }

    static class Rect extends Shape {
        int h;

        Rect(int w, int hh) {
            super(w);
            this.h = hh;
        }

        int area() {
            return this.size * this.h;
        }

        int name() {
            return 2;
        }
    }
}