public class GcTest {

    static Node keep;

    public static void main(String[] args) {
        int rounds = 0;
        int i = 0;
        while (i < 3000) {
            Node t = new Node(i);
            t.next = new Node(i + 1);
            if (i % 500 == 0) {
                keep = t;
            }
            rounds = rounds + t.v + t.next.v;
            i = i + 1;
        }
        System.out.println(rounds);
        System.out.println(keep.v);
        System.out.println(keep.next.v);

        int j = 0;
        int acc = 0;
        while (j < 800) {
            int[] big = new int[512];
            big[0] = j;
            big[511] = j * 2;
            acc = acc + big[0] + big[511];
            j = j + 1;
        }
        System.out.println(acc);

        byte[] blob = new byte[4096];
        blob[0] = 7;
        blob[4095] = 9;
        System.out.println(blob[0] + blob[4095] + blob.length);

        System.out.println(chain(kept(200)));
    }

    static Node kept(int n) {
        Node head = null;
        int i = 0;
        while (i < n) {
            Node cur = new Node(i);
            cur.next = head;
            head = cur;
            i = i + 1;
        }
        return head;
    }

    static int chain(Node head) {
        int sum = 0;
        Node cur = head;
        while (cur != null) {
            sum = sum + cur.v;
            cur = cur.next;
        }
        return sum + (head == null ? 0 : 1);
    }

    static class Node {
        int v;
        Node next;

        Node(int x) {
            this.v = x;
        }
    }
}