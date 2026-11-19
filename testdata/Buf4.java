import bufs.ByteBuffer;

public class Buf4 {

    public static void main(String[] args) {
        ByteBuffer b = ByteBuffer.allocateDirect(32);
        b.putInt(0, 12345);
        b.putInt(4, -7);
        System.out.println(b.getInt(0));
        System.out.println(b.getInt(4));
    }
}