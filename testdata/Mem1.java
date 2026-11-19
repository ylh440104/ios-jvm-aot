public class Mem1 {

    public static void main(String[] args) {
        long h = org.lwjgl.system.MemoryUtil.nmemAlloc(32);
        System.out.println(h == 0 ? 0 : 1);
        System.out.println(org.lwjgl.system.MemoryUtil.nmemAllocSize(h));

        long a = org.lwjgl.system.MemoryUtil.nmemAddress(h);
        System.out.println(a == 0 ? 0 : 1);

        org.lwjgl.system.MemoryUtil.memPutLong(a, 1234567890L);
        org.lwjgl.system.MemoryUtil.memPutLong(a + 8, 42L);
        org.lwjgl.system.MemoryUtil.memPutLong(a + 16, -7L);

        System.out.println(org.lwjgl.system.MemoryUtil.memGetLong(a));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetLong(a + 8));
        System.out.println(org.lwjgl.system.MemoryUtil.memGetLong(a + 16));

        org.lwjgl.system.MemoryUtil.memPutInt(a, 65535);
        System.out.println(org.lwjgl.system.MemoryUtil.memGetInt(a));

        org.lwjgl.system.MemoryUtil.nmemFree(h);
        System.out.println("done");
    }
}