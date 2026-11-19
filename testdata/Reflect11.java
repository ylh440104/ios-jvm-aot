public class Reflect11 {

    static class P {
        int a;
        int b;

        P(int a, int b) {
            this.a = a;
            this.b = b;
        }

        int v() {
            return this.a * 10 + this.b;
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> k = Class.forName("Reflect11$P");
        Class<?>[] sig = new Class<?>[] { int.class, int.class };
        Object c = k.getDeclaredConstructor(sig);
        System.out.println(c == null ? 0 : 1);
        P p = (P) k.getDeclaredConstructor(sig).newInstance(new Object[] { 3, 4 });
        System.out.println(p.v());
    }
}