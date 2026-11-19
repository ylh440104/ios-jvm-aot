public class Str2 {

    public static void main(String[] args) {
        String s = "alpha,beta,gamma";
        String[] parts = s.split(",");
        System.out.println(parts.length);
        for (int i = 0; i < parts.length; i++) {
            System.out.println(parts[i]);
        }

        String[] more = "a-b-c-d".split("-");
        System.out.println(more.length);
        System.out.println(more[0]);
        System.out.println(more[3]);

        String t = "  spaced  ";
        System.out.println("[" + t.strip() + "]");
        System.out.println("[" + t.trim() + "]");

        System.out.println("ab".repeat(3));

        char[] cs = "hello".toCharArray();
        System.out.println(cs.length);
        System.out.println(cs[0]);
        System.out.println(cs[4]);

        System.out.println("abcdef".regionMatches(1, "bcd", 0, 3) ? 1 : 0);
        System.out.println("abcdef".regionMatches(1, "xyz", 0, 3) ? 1 : 0);

        String[] empty = "abc".split("");
        System.out.println(empty.length);
    }
}