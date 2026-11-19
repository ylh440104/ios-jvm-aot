public class Wait1 {

    static final Object LOCK = new Object();
    static int value;
    static boolean ready;

    static class Producer implements Runnable {
        public void run() {
            synchronized (LOCK) {
                value = 42;
                ready = true;
                LOCK.notifyAll();
            }
        }
    }

    static class Consumer implements Runnable {
        public void run() {
            synchronized (LOCK) {
                while (!ready) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                System.out.println(value);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Thread c = new Thread(new Consumer());
        Thread p = new Thread(new Producer());
        c.start();
        Thread.sleep(30);
        p.start();
        p.join();
        c.join();
        System.out.println("done");

        Object m = new Object();
        synchronized (m) {
            m.notify();
        }
        System.out.println("notify ok");

        synchronized (m) {
            m.notifyAll();
        }
        System.out.println("notifyAll ok");

        int r = illegalNotify(new Object());
        System.out.println(r);
    }

    static int illegalNotify(Object o) {
        try {
            o.notify();
            return 1;
        } catch (IllegalMonitorStateException e) {
            return 0;
        }
    }
}