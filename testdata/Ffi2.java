public class Ffi2 {

    static long lib;
    static long pAbs;
    static long pStrlen;

    public static void main(String[] args) {
        lib = org.lwjgl.system.Library.loadNative(Ffi2.class, "libc.so.6");
        System.out.println(lib == 0 ? 0 : 1);

        pAbs = Call.sym(lib, "abs");
        System.out.println(pAbs == 0 ? 0 : 1);

        pStrlen = Call.sym(lib, "strlen");
        System.out.println(pStrlen == 0 ? 0 : 1);

        System.out.println(Call.call1(pAbs, -123L));
        System.out.println(Call.call1(pAbs, 456L));

        long buf = org.lwjgl.system.MemoryUtil.nmemAlloc(8);
        long a = org.lwjgl.system.MemoryUtil.nmemAddress(buf);
        org.lwjgl.system.MemoryUtil.memPutLong(a, 0x004300420041L);
        System.out.println(Call.call1(pStrlen, a));
        org.lwjgl.system.MemoryUtil.nmemFree(buf);

        long bad = org.lwjgl.system.Library.loadNative(Ffi2.class, "libnope_not_here.so");
        System.out.println(bad == 0 ? 0 : 1);
    }
}