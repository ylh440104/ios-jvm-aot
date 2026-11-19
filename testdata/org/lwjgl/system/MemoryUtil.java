package org.lwjgl.system;
public final class MemoryUtil {
    public static native long nmemAlloc(long size);
    public static native void nmemFree(long ptr);
    public static native long nmemAddress(long ptr);
    public static native long nmemAllocSize(long ptr);
    public static native void memPutLong(long addr, long value);
    public static native long memGetLong(long addr);
    public static native void memPutInt(long addr, int value);
    public static native int memGetInt(long addr);
}
