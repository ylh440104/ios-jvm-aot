public class Reflect2 {

    static class Item {
        int id;

        Item(int id) {
            this.id = id;
        }

        int get() {
            return this.id;
        }
    }

    public static void main(String[] args) {
        Item a = new Item(11);
        Item b = new Item(22);
        Class<?> ca = a.getClass();
        Class<?> cb = b.getClass();
        System.out.println(ca == cb ? 1 : 0);
        System.out.println(ca.getName());
        System.out.println(ca.getSimpleName());
        System.out.println(a.get() + b.get());

        Class<?> arr = new int[3].getClass();
        System.out.println(arr.getName());

        Class<?> found = null;
        try {
            found = Class.forName("Reflect2$Item");
        } catch (ClassNotFoundException e) {
            found = null;
        }
        System.out.println(found == null ? 0 : 1);
        System.out.println(found == ca ? 1 : 0);
    }
}