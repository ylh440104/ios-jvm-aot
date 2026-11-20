public class Sw1 {

    static int dense(int x) {
        switch (x) {
            case 0:
                return 100;
            case 1:
                return 101;
            case 2:
                return 102;
            case 3:
                return 103;
            default:
                return -1;
        }
    }

    static int sparse(int x) {
        switch (x) {
            case -100:
                return 1;
            case 7:
                return 2;
            case 9000:
                return 3;
            default:
                return 0;
        }
    }

    static String str(int x) {
        switch (x) {
            case 10:
                return "ten";
            case 20:
                return "twenty";
            default:
                return "other";
        }
    }

    public static void main(String[] args) {
        System.out.println(dense(0));
        System.out.println(dense(2));
        System.out.println(dense(3));
        System.out.println(dense(9));

        System.out.println(sparse(-100));
        System.out.println(sparse(7));
        System.out.println(sparse(9000));
        System.out.println(sparse(5));

        System.out.println(str(10));
        System.out.println(str(20));
        System.out.println(str(30));

        int total = 0;
        for (int i = 0; i < 6; i++) {
            switch (i) {
                case 0:
                    total += 1;
                    break;
                case 1:
                    total += 10;
                    break;
                case 2:
                    total += 100;
                    break;
                default:
                    total += 1000;
                    break;
            }
        }
        System.out.println(total);

        System.out.println("done");
    }
}