public class StaticsTest {

    static int counter = 7;
    static int total;

    public static void main(String[] args) {
        System.out.println(counter);
        counter = counter + 5;
        System.out.println(counter);
        int i = 0;
        while (i < 4) {
            total = total + counter;
            i = i + 1;
        }
        System.out.println(total);
        bump();
        bump();
        System.out.println(counter);
    }

    static void bump() {
        counter = counter + 100;
    }
}