package dev.cmc.aot;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LambdaSynthesizer {

    private static final Map<String, String> SITE_TO_CLASS = new LinkedHashMap<>();

    private LambdaSynthesizer() {
    }

    public static String lookup(String key) {
        return SITE_TO_CLASS.get(key);
    }

    public static String siteKey(String owner, String name, String desc, Object[] bsmArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append(owner).append('|').append(name).append('|').append(desc);
        for (Object a : bsmArgs) {
            if (a instanceof Handle h) {
                sb.append('|').append(h.getOwner()).append('.').append(h.getName()).append(h.getDesc()).append('#').append(h.getTag());
            } else if (a instanceof Type t) {
                sb.append('|').append(t.getDescriptor());
            }
        }
        return sb.toString();
    }

    public static void scan(List<ClassInfo> classes) {
        SITE_TO_CLASS.clear();
        int seq = 0;
        for (ClassInfo c : new ArrayList<>(classes)) {
            for (MethodNode mn : new ArrayList<>(c.methods())) {
                if (mn.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InvokeDynamicInsnNode indy)) {
                        continue;
                    }
                    Handle bsm = indy.bsm;
                    if (bsm == null || !"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())) {
                        continue;
                    }
                    String key = siteKey(c.name(), indy.name, indy.desc, indy.bsmArgs);
                    if (SITE_TO_CLASS.containsKey(key)) {
                        continue;
                    }
                    String cls = synth(classes, c, indy, seq++);
                    if (cls == null) {
                        continue;
                    }
                    SITE_TO_CLASS.put(key, cls);
                }
            }
        }
    }

    private static String synth(List<ClassInfo> classes, ClassInfo host, InvokeDynamicInsnNode indy, int seq) {
        if (indy.bsmArgs.length < 2) {
            return null;
        }
        Type samType = indy.bsmArgs[0] instanceof Type t0 ? t0 : null;
        Handle impl = indy.bsmArgs[1] instanceof Handle h1 ? h1 : null;
        if (samType == null || impl == null) {
            return null;
        }
        String itf = host.name() + "$$Lambda$" + seq;
        String samDesc = samType.getDescriptor();
        String samName = indy.name;
        Type[] captured = Type.getArgumentTypes(indy.desc);
        Type[] samArgs = Type.getArgumentTypes(samDesc);
        Type samRet = Type.getReturnType(samDesc);

        List<MethodNode> methods = new ArrayList<>();

        MethodNode ctor = new MethodNode();
        ctor.access = Opcodes.ACC_PUBLIC;
        ctor.name = "<init>";
        ctor.desc = "(" + repeatJ(captured.length) + ")V";
        ctor.maxLocals = 1 + captured.length;
        ctor.maxStack = 4 + captured.length;
        ctor.instructions = new InsnList();
        InsnList ci = ctor.instructions;
        ci.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ci.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        for (int i = 0; i < captured.length; i++) {
            ci.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ci.add(new VarInsnNode(Opcodes.LLOAD, i + 1));
            ci.add(new FieldInsnNode(Opcodes.PUTFIELD, itf, "c" + i, "J"));
        }
        ci.add(new InsnNode(Opcodes.RETURN));
        methods.add(ctor);

        MethodNode sam = new MethodNode();
        sam.access = Opcodes.ACC_PUBLIC;
        sam.name = samName;
        sam.desc = samDesc;
        sam.maxLocals = 1 + captured.length + samArgs.length + 4;
        sam.maxStack = 8;
        sam.instructions = new InsnList();
        InsnList si = sam.instructions;
        int localSlot = 1 + captured.length;
        boolean instanceImpl = impl.getTag() == Opcodes.H_INVOKEVIRTUAL
            || impl.getTag() == Opcodes.H_INVOKEINTERFACE;
        boolean ctorImpl = impl.getTag() == Opcodes.H_NEWINVOKESPECIAL;
        if (ctorImpl) {
            si.add(new TypeInsnNode(Opcodes.NEW, impl.getOwner()));
            si.add(new InsnNode(Opcodes.DUP));
            for (int i = 0; i < captured.length; i++) {
                loadArg(si, captured[i], localSlot++);
            }
            for (Type a : samArgs) {
                loadArg(si, a, localSlot++);
            }
            si.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, impl.getOwner(), impl.getName(), impl.getDesc(), false));
        } else if (instanceImpl) {
            si.add(new VarInsnNode(Opcodes.ALOAD, 1));
            for (int i = 1; i < captured.length; i++) {
                loadArg(si, captured[i], localSlot++);
            }
            for (Type a : samArgs) {
                loadArg(si, a, localSlot++);
            }
            int op = impl.getTag() == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            si.add(new MethodInsnNode(op, impl.getOwner(), impl.getName(), impl.getDesc(), op == Opcodes.INVOKEINTERFACE));
        } else {
            for (int i = 0; i < captured.length; i++) {
                loadArg(si, captured[i], localSlot++);
            }
            for (Type a : samArgs) {
                loadArg(si, a, localSlot++);
            }
            si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, impl.getOwner(), impl.getName(), impl.getDesc(), false));
        }
        si.add(returnFor(samRet));
        methods.add(sam);

        ClassInfo ci2 = new ClassInfo(itf, "java/lang/Object", new java.util.ArrayList<>(), 0, methods, capturedFields(captured));
        classes.add(ci2);
        return itf;
    }

    private static void loadArg(InsnList si, Type t, int slot) {
        switch (t.getSort()) {
            case Type.LONG -> si.add(new VarInsnNode(Opcodes.LLOAD, slot));
            case Type.DOUBLE -> si.add(new VarInsnNode(Opcodes.DLOAD, slot));
            case Type.FLOAT -> si.add(new VarInsnNode(Opcodes.FLOAD, slot));
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> si.add(new VarInsnNode(Opcodes.ILOAD, slot));
            default -> si.add(new VarInsnNode(Opcodes.ALOAD, slot));
        }
    }

    private static AbstractInsnNode returnFor(Type ret) {
        return switch (ret.getSort()) {
            case Type.VOID -> new InsnNode(Opcodes.RETURN);
            case Type.LONG -> new InsnNode(Opcodes.LRETURN);
            case Type.DOUBLE -> new InsnNode(Opcodes.DRETURN);
            case Type.FLOAT -> new InsnNode(Opcodes.FRETURN);
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> new InsnNode(Opcodes.IRETURN);
            default -> new InsnNode(Opcodes.ARETURN);
        };
    }

    private static List<FieldInfo> capturedFields(Type[] captured) {
        List<FieldInfo> out = new ArrayList<>();
        for (int i = 0; i < captured.length; i++) {
            out.add(new FieldInfo("c" + i, "J", false));
        }
        return out;
    }

    private static String repeatJ(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('J');
        }
        return sb.toString();
    }
}