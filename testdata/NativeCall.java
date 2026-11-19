public final class NativeCall {
    public static native long invoke1(long fn, long a);
    public static native long invokeF(long fn, int retk, long slots, int nargs, long kinds);
    public static native long invokeD(long fn, int retk, long slots, int nargs, long kinds);
    public static native long arrayAddr(Object arr);
}
