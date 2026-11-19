package dev.cmc.aot;

import java.util.HashMap;
import java.util.Map;

final class FfiSupport {

    private FfiSupport() {
    }

    static final class Bind {
        final String cName;
        final String ret;
        final String[] args;

        Bind(String cName, String ret, String... args) {
            this.cName = cName;
            this.ret = ret;
            this.args = args;
        }
    }

    private static final Map<String, Bind> TABLE = new HashMap<>();

    private static void reg(String owner, String name, String desc, String cName, String ret, String... args) {
        TABLE.put(owner + "." + name + desc, new Bind(cName, ret, args));
    }

    static {
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;ZLjava/lang/String;Z)J",
            "aot_ffi_open", "i64", "i64");
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;Z)V",
            "aot_ffi_open", "i64", "i64");
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;)J",
            "aot_ffi_open", "i64", "i64");

        reg("org/lwjgl/system/SharedLibrary", "getFunctionAddress", "(Ljava/lang/CharSequence;)J",
            "aot_ffi_sym", "i64", "i64", "i64");
        reg("org/lwjgl/system/Library", "getFunctionAddress", "(JLjava/lang/CharSequence;)J",
            "aot_ffi_sym", "i64", "i64", "i64");

        reg("org/lwjgl/system/MemoryUtil", "nmemAlloc", "(J)J", "aot_mem_alloc", "i64", "i64");
        reg("org/lwjgl/system/MemoryUtil", "nmemFree", "(J)V", "aot_mem_free", null, "i64");
        reg("org/lwjgl/system/MemoryUtil", "nmemAddress", "(Ljava/nio/ByteBuffer;)J",
            "aot_mem_addr", "i64", "i64");
        reg("org/lwjgl/system/MemoryUtil", "nmemAddress", "(J)J", "aot_mem_addr", "i64", "i64");
        reg("org/lwjgl/system/MemoryUtil", "nmemAllocSize", "(J)J", "aot_mem_size", "i64", "i64");

        reg("org/lwjgl/system/MemoryUtil", "memPutInt", "(JI)V", "aot_addr_put4_at", null, "i64", "i32");
        reg("org/lwjgl/system/MemoryUtil", "memPutLong", "(JJ)V", "aot_addr_put8_at", null, "i64", "i64");
        reg("org/lwjgl/system/MemoryUtil", "memGetInt", "(J)I", "aot_addr_get4_at", "i32", "i64");
        reg("org/lwjgl/system/MemoryUtil", "memGetLong", "(J)J", "aot_addr_get8_at", "i64", "i64");

        reg("org/lwjgl/system/PointerBuffer", "get", "(I)J", "aot_addr_get8", "i64", "i64", "i64");

        reg("NativeCall", "invoke0", "(J)J", "aot_ffi_call0", "i64", "i64");
        reg("NativeCall", "invoke1", "(JJ)J", "aot_ffi_call1", "i64", "i64", "i64");
        reg("NativeCall", "invoke2", "(JJJ)J", "aot_ffi_call2", "i64", "i64", "i64", "i64");
        reg("NativeCall", "invoke3", "(JJJJ)J", "aot_ffi_call3", "i64", "i64", "i64", "i64", "i64");
        reg("NativeCall", "invoke4", "(JJJJJ)J", "aot_ffi_call4", "i64", "i64", "i64", "i64", "i64", "i64");
        reg("NativeCall", "invoke5", "(JJJJJJ)J", "aot_ffi_call5", "i64", "i64", "i64", "i64", "i64", "i64", "i64");
        reg("NativeCall", "invoke6", "(JJJJJJJ)J", "aot_ffi_call6", "i64", "i64", "i64", "i64", "i64", "i64", "i64", "i64");
    }

    static Bind lookup(String owner, String name, String desc) {
        Bind b = TABLE.get(owner + "." + name + desc);
        if (b != null) {
            return b;
        }
        return TABLE.get(owner + "." + name);
    }

    static Bind lookupByKey(String key) {
        return TABLE.get(key);
    }
}