import bufs.ByteBuffer;

public class Buf1 {

    public static void main(String[] args) {
        ByteBuffer b = ByteBuffer.allocateDirect(32);
        System.out.println(b == null ? 0 : 1);
        System.out.println(b.capacity());
        System.out.println(b.position());
        System.out.println(b.limit());
        System.out.println(b.remaining());

        b.putInt(0, 12345);
        b.putInt(4, -7);
        b.putLong(8, 9000000000L);
        System.out.println(b.getInt(0));
        System.out.println(b.getInt(4));
        System.out.println(b.getLong(8));

        b.putFloat(0, 1.5f);
        System.out.println(b.getFloat(0) == 1.5f ? 1 : 0);

        ByteBuffer w = ByteBuffer.allocateDirect(4);
        w.put((byte) 65);
        w.put((byte) 66);
        System.out.println(w.position());
        System.out.println(w.get(0));
        System.out.println(w.get(1));
        System.out.println(w.remaining());

        w.flip();
        System.out.println(w.position());
        System.out.println(w.limit());
        System.out.println(w.remaining());

        w.clear();
        System.out.println(w.position());
        System.out.println(w.limit());

        w.position(2);
        System.out.println(w.position());
        w.rewind();
        System.out.println(w.position());

        System.out.println("done");
    }
}