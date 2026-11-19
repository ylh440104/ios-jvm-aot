import java.lang.reflect.Method;

public class Reflect8 {

    static class Calc {
        int noArgs() {
            return 42;
        }
    }

    public static void main(String[] args) throws Exception {
        Calc c = new Calc();
        Class<?> k = c.getClass();
        System.out.println(k.getName());
        Class<?>[] p = new Class<?>[0];
        Method m = k.getDeclaredMethod("noArgs", p);
        System.out.println(m == null ? 0 : 1);
    }
}