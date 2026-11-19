public final class Call {
    public static long sym(long lib, String name) {
        return org.lwjgl.system.Library.getFunctionAddress(lib, name);
    }
    public static long call1(long fn, long a) {
        return NativeCall.invoke1(fn, a);
    }
}
