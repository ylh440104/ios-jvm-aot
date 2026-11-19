public class Ffi3 {

    static final int K_I32 = 1;
    static final int K_I64 = 2;
    static final int K_F32 = 3;
    static final int K_F64 = 4;

    public static void main(String[] args) {
        long lib = org.lwjgl.system.Library.loadNative(Ffi3.class, "libm.so.6");
        System.out.println(lib == 0 ? 0 : 1);

        long pSin = org.lwjgl.system.Library.getFunctionAddress(lib, "sin");
        long pSqrt = org.lwjgl.system.Library.getFunctionAddress(lib, "sqrt");
        long pPow = org.lwjgl.system.Library.getFunctionAddress(lib, "pow");
        System.out.println(pSin == 0 ? 0 : 1);
        System.out.println(pSqrt == 0 ? 0 : 1);
        System.out.println(pPow == 0 ? 0 : 1);

        System.out.println(fmt(callD1(pSin, 0.0)));
        System.out.println(fmt(callD1(pSqrt, 16.0)));
        System.out.println(fmt(callD2(pPow, 2.0, 10.0)));

        long buf = org.lwjgl.system.MemoryUtil.nmemAlloc(16);
        long a = org.lwjgl.system.MemoryUtil.nmemAddress(buf);

        org.lwjgl.system.MemoryUtil.memPutFloat(a, 2.5f);
        System.out.println(fmtF(org.lwjgl.system.MemoryUtil.memGetFloat(a)));

        org.lwjgl.system.MemoryUtil.memPutDouble(a + 8, 3.25);
        System.out.println(fmt(org.lwjgl.system.MemoryUtil.memGetDouble(a + 8)));

        org.lwjgl.system.MemoryUtil.memPutByte(a, (byte) 200);
        System.out.println(org.lwjgl.system.MemoryUtil.memGetByte(a));

        org.lwjgl.system.MemoryUtil.memPutShort(a, (short) 70000);
        System.out.println(org.lwjgl.system.MemoryUtil.memGetShort(a));

        org.lwjgl.system.MemoryUtil.nmemFree(buf);
        System.out.println("done");
    }

    static double callD1(long fn, double x) {
        long[] slots = new long[1];
        slots[0] = bitsOf(x);
        int[] kinds = new int[1];
        kinds[0] = K_F64;
        return doubleOf(NativeCall.invokeD(fn, K_F64, addrOf(slots), 1, addrOf(kinds)));
    }

    static double callD2(long fn, double x, double y) {
        long[] slots = new long[2];
        slots[0] = bitsOf(x);
        slots[1] = bitsOf(y);
        int[] kinds = new int[2];
        kinds[0] = K_F64;
        kinds[1] = K_F64;
        return doubleOf(NativeCall.invokeD(fn, K_F64, addrOf(slots), 2, addrOf(kinds)));
    }

    static long addrOf(long[] a) {
        return NativeCall.arrayAddr(a);
    }

    static long addrOf(int[] a) {
        return NativeCall.arrayAddr(a);
    }

    static long bitsOf(double d) {
        return Double.doubleToLongBits(d);
    }

    static double doubleOf(long bits) {
        return Double.longBitsToDouble(bits);
    }

    static String fmt(double d) {
        if (d == 0.0) {
            return "0";
        }
        long scaled = (long) (d * 1000.0 + 0.5);
        return (scaled / 1000) + "." + pad(Math.abs(scaled % 1000));
    }

    static String fmtF(float f) {
        return fmt((double) f);
    }

    static String pad(long v) {
        if (v < 10) {
            return "00" + v;
        }
        if (v < 100) {
            return "0" + v;
        }
        return "" + v;
    }

    static long abs(long v) {
        return v < 0 ? -v : v;
    }
}