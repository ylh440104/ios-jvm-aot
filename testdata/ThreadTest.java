public class ThreadTest {

    static int counter;

    static class Worker implements Runnable {
        int id;
        int rounds;

        Worker(int id, int rounds) {
            this.id = id;
            this.rounds = rounds;
        }

        public void run() {
            int i = 0;
            while (i < rounds) {
                ThreadTest.bump();
                i = i + 1;
            }
        }
    }

    static synchronized void bump() {
        counter = counter + 1;
    }

    public static void main(String[] args) {
        counter = 0;
        Worker w1 = new Worker(1, 1000);
        Worker w2 = new Worker(2, 1000);
        Thread t1 = new Thread(w1);
        Thread t2 = new Thread(w2);
        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            System.out.println(-1);
        }
        System.out.println(counter);
        System.out.println(counter == 2000 ? 1 : 0);
    }
}