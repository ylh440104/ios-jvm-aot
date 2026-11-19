public final class NativeCall {
    public static native long invoke1(long fn, long a);
    public static native long invokeF(long fn, int retk, long slots, int nargs, long kinds);
    public static native long invokeD(long fn, int retk, long slots, int nargs, long kinds);
    public static native long arrayAddr(Object arr);
    public static native long bufAddr(Object buf);
    public static native long bufPosAddr(Object buf);
    public static native long bufBytes(Object buf);
    public static native int callBuf1(long fn, Object buf);
    public static native int callBuf2(long fn, int a, Object buf);
}
