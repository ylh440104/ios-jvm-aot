public class Wait2 {

    public static void main(String[] args) throws Exception {
        Object o = new Object();
        long t0 = System.currentTimeMillis();
        synchronized (o) {
            o.wait(60);
        }
        long dt = System.currentTimeMillis() - t0;
        System.out.println(dt >= 50 && dt < 2000 ? 1 : 0);

        int[] done = new int[1];
        Thread w = new Thread(new Runnable() {
            public void run() {
                synchronized (o) {
                    try {
                        o.wait(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    done[0] = 1;
                }
            }
        });
        w.start();
        Thread.sleep(40);
        synchronized (o) {
            o.notifyAll();
        }
        w.join();
        System.out.println(done[0]);

        System.out.println(illegalWait(o));

        synchronized (o) {
            o.notify();
            o.notifyAll();
        }
        System.out.println("ok");
    }

    static int illegalWait(Object o) {
        try {
            o.wait(1);
            return 1;
        } catch (IllegalMonitorStateException e) {
            return 0;
        } catch (InterruptedException e) {
            return 2;
        }
    }
}