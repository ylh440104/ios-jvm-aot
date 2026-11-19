import bufs.ByteBuffer;

public class Buf3 {

    public static void main(String[] args) {
        ByteBuffer b = ByteBuffer.allocateDirect(32);
        System.out.println("a");
        b.putInt(0, 12345);
        System.out.println("b");
        b.putInt(4, -7);
        System.out.println("c");
        b.putLong(8, 9000000000L);
        System.out.println("d");
        System.out.println(b.getInt(0));
        System.out.println(b.getInt(4));
        System.out.println(b.getLong(8));
        System.out.println("e");
    }
}