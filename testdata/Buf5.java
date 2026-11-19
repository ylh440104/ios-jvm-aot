import bufs.ByteBuffer;

public class Buf5 {

    static final int K_I32 = 1;
    static final int K_I64 = 2;
    static final int K_F64 = 4;

    public static void main(String[] args) {
        long lib = org.lwjgl.system.Library.loadNative(Buf5.class, "libc.so.6");
        System.out.println(lib == 0 ? 0 : 1);
        long pStrlen = org.lwjgl.system.Library.getFunctionAddress(lib, "strlen");
        System.out.println(pStrlen == 0 ? 0 : 1);

        ByteBuffer b = ByteBuffer.allocateDirect(16);
        b.put(0, (byte) 65);
        b.put(1, (byte) 66);
        b.put(2, (byte) 67);
        b.put(3, (byte) 0);
        System.out.println("wrote");

        long addr = NativeCall.bufAddr(b);
        System.out.println(addr == 0 ? 0 : 1);
        System.out.println(NativeCall.bufBytes(b));

        int n = NativeCall.callBuf1(pStrlen, b);
        System.out.println(n);

        b.put(3, (byte) 68);
        b.put(4, (byte) 0);
        System.out.println(NativeCall.callBuf1(pStrlen, b));

        b.position(1);
        System.out.println(NativeCall.callBuf1(pStrlen, b));

        ByteBuffer empty = ByteBuffer.allocateDirect(4);
        empty.put(0, (byte) 0);
        System.out.println(NativeCall.callBuf1(pStrlen, empty));

        System.out.println("done");
    }
}