public class ThreadGc {

    static int done;

    static synchronized void hit() {
        done = done + 1;
    }

    static class Alloc implements Runnable {
        int rounds;

        Alloc(int rounds) {
            this.rounds = rounds;
        }

        public void run() {
            int i = 0;
            while (i < rounds) {
                StringBuilder sb = new StringBuilder();
                sb.append("item");
                sb.append('-');
                sb.append(i);
                String s = sb.toString();
                if (s.length() > 0) {
                    ThreadGc.hit();
                }
                i = i + 1;
            }
        }
    }

    public static void main(String[] args) {
        done = 0;
        Thread[] ts = new Thread[4];
        int k = 0;
        while (k < 4) {
            ts[k] = new Thread(new Alloc(500));
            k = k + 1;
        }
        k = 0;
        while (k < 4) {
            ts[k].start();
            k = k + 1;
        }
        k = 0;
        while (k < 4) {
            try {
                ts[k].join();
            } catch (InterruptedException e) {
                done = -1;
            }
            k = k + 1;
        }
        System.out.println(done);
        System.out.println(done == 2000 ? 1 : 0);
    }
}