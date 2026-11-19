public class StrTest {

    public static void main(String[] args) {
        String a = "hello";
        String b = "world";
        String c = "hello";

        System.out.println(a.length());
        System.out.println(a.charAt(0));
        System.out.println(a.charAt(4));
        System.out.println(a.equals(b) ? 1 : 0);
        System.out.println(a.equals(c) ? 1 : 0);
        System.out.println(a.equals("hello") ? 1 : 0);
        System.out.println(a.isEmpty() ? 1 : 0);
        System.out.println("".isEmpty() ? 1 : 0);

        System.out.println("hello".hashCode());
        System.out.println("".hashCode());
        System.out.println("a".hashCode());

        System.out.println(a.substring(1));
        System.out.println(a.substring(1, 3));
        System.out.println(a.indexOf(108));
        System.out.println(a.indexOf(122));

        System.out.println(a.compareTo(b));
        System.out.println(b.compareTo(a));
        System.out.println(a.compareTo(c));

        System.out.println(a.concat(b));
        System.out.println(a.concat("!"));

        String u = "中文";
        System.out.println(u.length());
        System.out.println(u.charAt(0));

        System.out.println(describe(a));
        System.out.println(describe(null));
    }

    static String describe(Object o) {
        if (o == null) {
            return "null-value";
        }
        return "obj";
    }
}