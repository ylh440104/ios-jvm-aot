package dev.cmc.aot;

import java.util.HashMap;
import java.util.Map;

final class FfiSupport {

    private FfiSupport() {
    }

    static final int K_VOID = 0;
    static final int K_I32 = 1;
    static final int K_I64 = 2;
    static final int K_F32 = 3;
    static final int K_F64 = 4;
    static final int K_PTR = 5;
    static final int K_BUF = 6;

    static final class Bind {
        final String cName;
        final int ret;
        final int[] args;

        Bind(String cName, int ret, int... args) {
            this.cName = cName;
            this.ret = ret;
            this.args = args;
        }
    }

    private static final Map<String, Bind> TABLE = new HashMap<>();

    private static void reg(String owner, String name, String desc, String cName, int ret, int... args) {
        TABLE.put(owner + "." + name + desc, new Bind(cName, ret, args));
    }

    static {
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;ZLjava/lang/String;Z)J",
            "aot_ffi_open", K_PTR, K_PTR);
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;Z)V",
            "aot_ffi_open", K_PTR, K_PTR);
        reg("org/lwjgl/system/Library", "loadNative", "(Ljava/lang/Class;Ljava/lang/String;)J",
            "aot_ffi_open", K_PTR, K_PTR);

        reg("org/lwjgl/system/SharedLibrary", "getFunctionAddress", "(Ljava/lang/CharSequence;)J",
            "aot_ffi_sym_nohandle", K_PTR, K_PTR);
        reg("org/lwjgl/system/Library", "getFunctionAddress", "(JLjava/lang/CharSequence;)J",
            "aot_ffi_sym", K_PTR, K_PTR, K_PTR);

        reg("org/lwjgl/system/MemoryUtil", "nmemAlloc", "(J)J", "aot_mem_alloc", K_PTR, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "nmemFree", "(J)V", "aot_mem_free", K_VOID, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "nmemAddress", "(Ljava/nio/ByteBuffer;)J",
            "aot_mem_addr", K_PTR, K_PTR);
        reg("org/lwjgl/system/MemoryUtil", "nmemAddress", "(J)J", "aot_mem_addr", K_PTR, K_PTR);
        reg("org/lwjgl/system/MemoryUtil", "nmemAllocSize", "(J)J", "aot_mem_size", K_I64, K_PTR);

        reg("org/lwjgl/system/MemoryUtil", "memPutInt", "(JI)V", "aot_addr_put4_at", K_VOID, K_I64, K_I32);
        reg("org/lwjgl/system/MemoryUtil", "memPutLong", "(JJ)V", "aot_addr_put8_at", K_VOID, K_I64, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "memGetInt", "(J)I", "aot_addr_get4_at", K_I32, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "memGetLong", "(J)J", "aot_addr_get8_at", K_I64, K_PTR);

        reg("org/lwjgl/system/MemoryUtil", "memPutFloat", "(JF)V", "aot_addr_putf_at", K_VOID, K_I64, K_F32);
        reg("org/lwjgl/system/MemoryUtil", "memPutDouble", "(JD)V", "aot_addr_putd_at", K_VOID, K_I64, K_F64);
        reg("org/lwjgl/system/MemoryUtil", "memGetFloat", "(J)F", "aot_addr_getf_at", K_F32, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "memGetDouble", "(J)D", "aot_addr_getd_at", K_F64, K_I64);

        reg("org/lwjgl/system/MemoryUtil", "memPutByte", "(JB)V", "aot_addr_put1_at", K_VOID, K_I64, K_I32);
        reg("org/lwjgl/system/MemoryUtil", "memPutShort", "(JS)V", "aot_addr_put2_at", K_VOID, K_I64, K_I32);
        reg("org/lwjgl/system/MemoryUtil", "memGetByte", "(J)B", "aot_addr_get1_at", K_I32, K_I64);
        reg("org/lwjgl/system/MemoryUtil", "memGetShort", "(J)S", "aot_addr_get2_at", K_I32, K_I64);

        reg("org/lwjgl/system/MemoryUtil", "memSetMemory", "(JJB)J", "aot_mem_set", K_I64, K_I64, K_I64, K_I32);
        reg("org/lwjgl/system/MemoryUtil", "memCopyMemory", "(JJJ)J", "aot_mem_copy", K_I64, K_I64, K_I64, K_I64);

        reg("org/lwjgl/system/MemoryStack", "stackPush", "()J", "aot_stack_push", K_I64);
        reg("org/lwjgl/system/MemoryStack", "stackPop", "()V", "aot_stack_pop", K_VOID);
        reg("org/lwjgl/system/MemoryStack", "nstackMalloc", "(J)J", "aot_stack_malloc", K_I64, K_I64);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocFloat", "(I)J", "aot_stack_malloc", K_I64, K_I32);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocDouble", "(I)J", "aot_stack_malloc", K_I64, K_I32);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocInt", "(I)J", "aot_stack_malloc", K_I64, K_I32);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocLong", "(I)J", "aot_stack_malloc", K_I64, K_I32);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocByte", "(I)J", "aot_stack_malloc", K_I64, K_I32);
        reg("org/lwjgl/system/MemoryStack", "nstackMallocShort", "(I)J", "aot_stack_malloc", K_I64, K_I32);

        reg("NativeCall", "invokeF", "(JIJIJ)J", "aot_ffi_invoke", K_I64,
            K_I64, K_I32, K_I64, K_I32, K_I64);
        reg("NativeCall", "invoke1", "(JJ)J", "aot_ffi_call1", K_I64, K_I64, K_I64);
        reg("NativeCall", "invokeD", "(JIJIJ)J", "aot_ffi_invoke", K_I64,
            K_I64, K_I32, K_I64, K_I32, K_I64);
        reg("NativeCall", "arrayAddr", "(Ljava/lang/Object;)J", "aot_object_array_ptr", K_PTR, K_PTR);
        reg("NativeCall", "bufAddr", "(Ljava/lang/Object;)J", "aot_buf_arg_addr", K_PTR, K_PTR);
        reg("NativeCall", "bufPosAddr", "(Ljava/lang/Object;)J", "aot_buf_arg_pos_addr", K_PTR, K_PTR);
        reg("NativeCall", "bufBytes", "(Ljava/lang/Object;)J", "aot_buf_arg_bytes", K_I64, K_PTR);
        reg("NativeCall", "callBuf1", "(JLjava/lang/Object;)I", "aot_ffi_call_buf1", K_I32, K_PTR, K_BUF);
        reg("NativeCall", "callBuf2", "(JILjava/lang/Object;)I", "aot_ffi_call_buf2", K_I32, K_PTR, K_I32, K_BUF);
    }

    static Bind lookup(String owner, String name, String desc) {
        Bind b = TABLE.get(owner + "." + name + desc);
        if (b != null) {
            return b;
        }
        return TABLE.get(owner + "." + name);
    }
}