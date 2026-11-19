public class Fmt1 {

    public static void main(String[] args) {
        System.out.println(String.format("hello %s", "world"));
        System.out.println(String.format("n=%d", 42));
        System.out.println(String.format("neg=%d", -7));
        System.out.println(String.format("%d and %d and %d", 1, 2, 3));
        System.out.println(String.format("%d%% done", 50));
        System.out.println(String.format("[%5d]", 42));
        System.out.println(String.format("[%05d]", 42));
        System.out.println(String.format("[%8s]", "ab"));
        System.out.println(String.format("ch=%c", 'X'));
        System.out.println(String.format("bool=%b", true));
        System.out.println(String.format("bool=%b", false));
        System.out.println(String.format("hex=%x", 255));
        System.out.println(String.format("no args"));

        System.out.println(Integer.parseInt("123"));
        System.out.println(Integer.parseInt("-45"));
        System.out.println(Long.parseLong("9000000000"));

        System.out.println(validParse("12a"));
        System.out.println(validParse(""));
        System.out.println(validParse("+7"));
    }

    static int validParse(String s) {
        try {
            Integer.parseInt(s);
            return 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}