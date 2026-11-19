public class Reflect7 {

    static class Calc {
        int base;

        Calc(int base) {
            this.base = base;
        }
    }

    public static void main(String[] args) throws Exception {
        Calc c = new Calc(5);
        Class<?> k = c.getClass();
        System.out.println(k.getName());
        Class<?> again = c.getClass();
        System.out.println(k == again ? 1 : 0);
        Class<?> byName = Class.forName("Reflect7$Calc");
        System.out.println(byName == k ? 1 : 0);
    }
}