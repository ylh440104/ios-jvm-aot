public class Rec1 {

    public record Point(int x, int y) {
    }

    public record Named(String name, int id) {
    }

    public static void main(String[] args) {
        Point a = new Point(3, 4);
        Point b = new Point(3, 4);
        Point c = new Point(3, 5);

        System.out.println(a.equals(b));
        System.out.println(a.equals(c));
        System.out.println(a.equals(a));
        System.out.println(a.hashCode() == b.hashCode());
        System.out.println(a.toString());

        Named n1 = new Named("alice", 7);
        Named n2 = new Named("alice", 7);
        System.out.println(n1.equals(n2));
        System.out.println(n1.toString());

        System.out.println("done");
    }
}