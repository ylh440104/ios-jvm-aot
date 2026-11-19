package org.lwjgl.system;

public final class Library {
    public static native long loadNative(Class<?> context, String name);
    public static native long getFunctionAddress(long handle, CharSequence name);
}
