package dev.cmc.aot;

import java.util.HashMap;
import java.util.Map;

public final class Natives {

    public static final class Sig {
        public final String cName;
        public final int argCount;
        public final boolean returnsValue;
        public final boolean isVoidStringArg;

        Sig(String cName, int argCount, boolean returnsValue, boolean isVoidStringArg) {
            this.cName = cName;
            this.argCount = argCount;
            this.returnsValue = returnsValue;
            this.isVoidStringArg = isVoidStringArg;
        }
    }

    private static final Map<String, Sig> TABLE = new HashMap<>();

    static {
        register("java/io/PrintStream", "println", "(Ljava/lang/String;)V", "print_str", 1, false, true);
        register("java/io/PrintStream", "println", "(I)V", "print_int", 1, false, false);
        register("java/io/PrintStream", "println", "(J)V", "print_long", 1, false, false);
        register("java/io/PrintStream", "println", "(D)V", "print_double", 1, false, false);
        register("java/io/PrintStream", "println", "(F)V", "print_float", 1, false, false);
        register("java/io/PrintStream", "println", "(Z)V", "print_bool", 1, false, false);
        register("java/io/PrintStream", "println", "(C)V", "print_char", 1, false, false);
        register("java/io/PrintStream", "print", "(C)V", "print_char_raw", 1, false, false);
        register("java/io/PrintStream", "println", "()V", "print_nl", 0, false, false);
        register("java/io/PrintStream", "print", "(Ljava/lang/String;)V", "print_str_raw", 1, false, true);
        register("java/io/PrintStream", "print", "(I)V", "print_int_raw", 1, false, false);
        register("java/io/PrintStream", "print", "(J)V", "print_long_raw", 1, false, false);
        register("java/io/PrintStream", "print", "(D)V", "print_double_raw", 1, false, false);
        register("java/io/PrintStream", "print", "(F)V", "print_float_raw", 1, false, false);
        register("java/lang/Math", "abs", "(I)I", "math_abs", 1, true, false);
        register("java/lang/Math", "max", "(II)I", "math_max", 2, true, false);
        register("java/lang/Math", "min", "(II)I", "math_min", 2, true, false);
        register("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", "sb_append_str", 1, true, true);
        register("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", "sb_append_int", 1, true, false);
        register("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", "sb_to_string", 0, true, false);
    }

    private Natives() {
    }

    private static void register(String owner, String name, String desc, String cName, int argCount, boolean returnsValue, boolean isVoidStringArg) {
        TABLE.put(owner + "." + name + desc, new Sig(cName, argCount, returnsValue, isVoidStringArg));
    }

    public static Sig lookup(String owner, String name, String desc) {
        return TABLE.get(owner + "." + name + desc);
    }

    public static boolean isSystemOut(String owner, String name) {
        return "java/lang/System".equals(owner) && "out".equals(name);
    }
}