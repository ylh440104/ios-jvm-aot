public class TypesTest {

    public static void main(String[] args) {
        Shape a = new Circle(2);
        Shape b = new Box(3);
        Object c = a;

        System.out.println(a instanceof Circle ? 1 : 0);
        System.out.println(a instanceof Box ? 1 : 0);
        System.out.println(b instanceof Shape ? 1 : 0);
        System.out.println(c instanceof Shape ? 1 : 0);
        System.out.println(c instanceof Circle ? 1 : 0);

        Circle cc = (Circle) a;
        System.out.println(cc.r);
        Box bb = (Box) b;
        System.out.println(bb.side);

        Shape d = (Shape) c;
        System.out.println(d.area());

        Object n = null;
        System.out.println(n instanceof Shape ? 1 : 0);
        Shape e = (Shape) n;
        System.out.println(e == null ? 1 : 0);
    }

    static class Shape {
        int area() {
            return 0;
        }
    }

    static class Circle extends Shape {
        int r;

        Circle(int x) {
            this.r = x;
        }

        int area() {
            return 3 * this.r * this.r;
        }
    }

    static class Box extends Shape {
        int side;

        Box(int x) {
            this.side = x;
        }

        int area() {
            return this.side * this.side;
        }
    }
}