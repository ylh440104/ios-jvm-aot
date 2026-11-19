public class Buf9 {

    public static int cmp(long a, long b) {
        int x = org.lwjgl.system.MemoryUtil.memGetInt(a);
        int y = org.lwjgl.system.MemoryUtil.memGetInt(b);
        return x - y;
    }

    public static void main(String[] args) {
        long libc = org.lwjgl.system.Library.loadNative(Buf9.class, "libc.so.6");
        System.out.println(libc == 0 ? 0 : 1);
        long pQsort = org.lwjgl.system.Library.getFunctionAddress(libc, "qsort");
        System.out.println(pQsort == 0 ? 0 : 1);

        long cmpPtr = NativeCall.callback("Buf9", "cmp", "(JJ)I");
        System.out.println(cmpPtr == 0 ? 0 : 1);

        long buf = org.lwjgl.system.MemoryUtil.nmemAlloc(20);
        org.lwjgl.system.MemoryUtil.memPutInt(buf, 50);
        org.lwjgl.system.MemoryUtil.memPutInt(buf + 4, 10);
        org.lwjgl.system.MemoryUtil.memPutInt(buf + 8, 40);
        org.lwjgl.system.MemoryUtil.memPutInt(buf + 12, 20);
        org.lwjgl.system.MemoryUtil.memPutInt(buf + 16, 30);

        System.out.println(NativeCall.callQsort(pQsort, buf, 5, 4, cmpPtr));

        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(buf));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(buf + 4));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(buf + 8));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(buf + 12));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(buf + 16));

        System.out.println("done");
    }
}