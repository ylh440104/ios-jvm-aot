import java.lang.reflect.Method;

public class Reflect9 {

    static class Simple {
        public String toString() {
            return "S";
        }
    }

    public static void main(String[] args) throws Exception {
        Simple s = new Simple();
        Class<?> k = s.getClass();
        Method m = k.getDeclaredMethod("toString", new Class<?>[0]);
        System.out.println(m == null ? 0 : 1);
        Object r = m.invoke(s, new Object[0]);
        System.out.println(r);
    }
}