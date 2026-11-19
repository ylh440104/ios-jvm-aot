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
    public static native void memPutFloat(long addr, float value);
    public static native float memGetFloat(long addr);
    public static native void memPutDouble(long addr, double value);
    public static native double memGetDouble(long addr);
    public static native void memPutByte(long addr, byte value);
    public static native byte memGetByte(long addr);
    public static native void memPutShort(long addr, short value);
    public static native short memGetShort(long addr);
}
