public class RefsTest {

    static Node head;

    public static void main(String[] args) {
        Node a = new Node(5);
        Node b = new Node(9);
        System.out.println(pick(a, b));
        System.out.println(pick(a, a));
        System.out.println(pick(null, b));
        System.out.println(pick(b, null));

        head = a;
        System.out.println(walk());
        head = b;
        System.out.println(walk());

        System.out.println(sameRef(a, b));
        System.out.println(sameRef(a, a));
    }

    static int pick(Node x, Node y) {
        if (x == null) {
            if (y == null) {
                return -1;
            }
            return y.v;
        }
        if (y != null && x != y) {
            return x.v + y.v;
        }
        return x.v;
    }

    static int walk() {
        if (head == null) {
            return 0;
        }
        return head.v * 100;
    }

    static int sameRef(Node p, Node q) {
        if (p == q) {
            return 1;
        }
        return 0;
    }

    static class Node {
        int v;

        Node(int z) {
            this.v = z;
        }
    }
}