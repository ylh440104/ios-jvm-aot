import java.util.ArrayList;
import java.util.List;

public class Type1 {

    interface Animal {
        String kind();
    }

    static class Dog implements Animal {
        public String kind() {
            return "dog";
        }
    }

    static class Puppy extends Dog {
    }

    public static void main(String[] args) {
        Dog d = new Dog();
        Puppy p = new Puppy();

        Class<?> cd = d.getClass();
        Class<?> cp = p.getClass();
        Class<?> ca = Animal.class;
        Class<?> co = Object.class;

        System.out.println(cd.getName());

        Class<?> s1 = cd.getSuperclass();
        System.out.println(s1 == null ? "null" : s1.getName());

        Class<?> s2 = cp.getSuperclass();
        System.out.println(s2 == null ? "null" : s2.getName());

        Class<?> s3 = ca.getSuperclass();
        System.out.println(s3 == null ? "null" : s3.getName());

        Class<?> s4 = co.getSuperclass();
        System.out.println(s4 == null ? "null" : s4.getName());

        System.out.println(ca.isInterface() ? 1 : 0);
        System.out.println(cd.isInterface() ? 1 : 0);

        System.out.println(ca.isAssignableFrom(cd) ? 1 : 0);
        System.out.println(ca.isAssignableFrom(cp) ? 1 : 0);
        System.out.println(cd.isAssignableFrom(cp) ? 1 : 0);
        System.out.println(cp.isAssignableFrom(cd) ? 1 : 0);
        System.out.println(co.isAssignableFrom(cd) ? 1 : 0);
        System.out.println(cd.isAssignableFrom(cd) ? 1 : 0);

        List<Animal> list = new ArrayList<Animal>();
        list.add(d);
        list.add(p);
        System.out.println(list.size());
        System.out.println(list.get(0).kind());
        System.out.println(list.get(1).kind());

        Object o = d;
        System.out.println(o instanceof Animal ? 1 : 0);
        System.out.println(o instanceof Puppy ? 1 : 0);
    }
}