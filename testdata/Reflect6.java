import java.lang.reflect.Method;

public class Reflect6 {

    static class Calc {
        int base;

        Calc(int base) {
            this.base = base;
        }

        int noArgs() {
            return this.base + 9;
        }

        public String toString() {
            return "Calc(" + this.base + ")";
        }
    }

    public static void main(String[] args) throws Exception {
        Calc c = new Calc(100);
        Class<?> k = c.getClass();
        Method m = k.getDeclaredMethod("noArgs", new Class<?>[0]);
        System.out.println(m == null ? 0 : 1);
        System.out.println(m.getName());

        Object r = m.invoke(c, new Object[0]);
        System.out.println(r);

        Calc d = new Calc(7);
        System.out.println(m.invoke(d, new Object[0]));
    }
}