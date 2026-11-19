public class SbTest {

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append("abc");
        sb.append(42);
        sb.append('-');
        sb.append(true);
        System.out.println(sb.toString());
        System.out.println(sb.length());

        StringBuilder t = new StringBuilder();
        t.append(1);
        t.append(2L);
        t.append(3.5);
        t.append('x');
        System.out.println(t.toString());

        StringBuilder r = new StringBuilder();
        r.append("hello");
        System.out.println(r.reverse().toString());

        StringBuilder w = new StringBuilder();
        w.append("x");
        w.append("y");
        System.out.println(w.toString());

        System.out.println(String.valueOf(123));
        System.out.println(String.valueOf(9L));
        System.out.println(String.valueOf(1.5));
        System.out.println(String.valueOf(true));
        System.out.println(String.valueOf('Z'));

        System.out.println(Integer.parseInt("456"));
        System.out.println(Integer.parseInt("-78"));
        System.out.println(Long.parseLong("123456789012"));

        System.out.println("HelloWorld".toLowerCase());
        System.out.println("HelloWorld".toUpperCase());
        System.out.println("  pad  ".trim());
        System.out.println("prefix-abc".startsWith("prefix") ? 1 : 0);
        System.out.println("prefix-abc".endsWith("abc") ? 1 : 0);
        System.out.println("prefix-abc".contains("-a") ? 1 : 0);
        System.out.println("prefix-abc".contains("zz") ? 1 : 0);
        System.out.println("a-b-c".indexOf("-b"));
        System.out.println("a-b-c".indexOf("c"));
        System.out.println("aXbXc".replace('X', '-'));
        System.out.println("banana".lastIndexOf('a'));
    }
}