package dev.cmc.aot;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MethodTranslator {

    private static final int STACK_SLOTS = 64;

    private static final class Slot {
        final String kind;
        final String value;

        Slot(String kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private final ClassInfo owner;
    private final MethodNode mn;
    private final StringPool pool;
    private final Set<String> declares;
    private final Layout layout;
    private final int localsSize;
    private final StringBuilder body = new StringBuilder();
    private final IdentityHashMap<LabelNode, String> labels = new IdentityHashMap<>();
    private final List<Slot> stack = new ArrayList<>();
    private final Frame<BasicValue>[] frames;
    private int insnIndex;
    private int tmp;
    private int labelSeq;
    private boolean terminated;
    private String handlerDispatch;
    private String dispatchCode;
    private boolean hasHandlers;
    private final List<String> handlerLabels = new ArrayList<>();
    private int syncDepth;
    private String syncLock;
    private static final Set<String> classLocks = new LinkedHashSet<>();

    private MethodTranslator(ClassInfo owner, MethodNode mn, StringPool pool, Set<String> declares, Layout layout, Frame<BasicValue>[] frames) {
        this.owner = owner;
        this.mn = mn;
        this.pool = pool;
        this.declares = declares;
        this.layout = layout;
        this.localsSize = Math.max(mn.maxLocals, 2 + TypeInfo.argKinds(mn.desc).size() * 2);
        this.frames = frames;
    }

    public static String translate(ClassInfo owner, MethodNode mn, StringPool pool, Set<String> declares, Layout layout) {
        Frame<BasicValue>[] frames = TypeInfo.analyze(owner, mn);
        return new MethodTranslator(owner, mn, pool, declares, layout, frames).run();
    }

    private String run() {
        boolean isInstance = !ClassParser.isStatic(mn);
        List<String> argKinds = TypeInfo.argKinds(mn.desc);
        StringBuilder sig = new StringBuilder();
        if (isInstance) {
            sig.append("i64 %this");
        }
        for (int i = 0; i < argKinds.size(); i++) {
            if (sig.length() > 0) {
                sig.append(", ");
            }
            String ak = argKinds.get(i);
            sig.append("F".equals(ak) ? "i32" : TypeInfo.llvm(ak)).append(" %a").append(i);
        }
        String retType = "I".equals(TypeInfo.returnKind(mn.desc)) || "F".equals(TypeInfo.returnKind(mn.desc)) ? "i32"
            : "V".equals(TypeInfo.returnKind(mn.desc)) ? "i32" : "i64";
        body.append("define ").append(retType).append(" @")
            .append(ClassParser.mangle(owner.name(), mn.name, mn.desc))
            .append("(").append(sig).append(") {\n");
        body.append("entry:\n");
        body.append("  %locals = alloca [").append(localsSize).append(" x i64]\n");
        body.append("  %stack = alloca [").append(STACK_SLOTS).append(" x i64]\n");
        body.append("  %sp = alloca i32\n");
        body.append("  store i32 0, ptr %sp\n");
        body.append("  %pc = alloca i32\n");
        body.append("  store i32 0, ptr %pc\n");
        declares.add("declare void @gc_frame_push(ptr, ptr, i32, ptr, ptr)");
        declares.add("declare void @gc_frame_pop(ptr)");
        body.append("  %ret = alloca [2 x i64]\n");
        body.append("  %frame = alloca [5 x i64]\n");
        body.append("  %lptr = getelementptr [").append(localsSize).append(" x i64], ptr %locals, i64 0, i32 0\n");
        body.append("  %sptr = getelementptr [").append(STACK_SLOTS).append(" x i64], ptr %stack, i64 0, i32 0\n");
        body.append("  call void @gc_frame_push(ptr %frame, ptr %lptr, i32 ").append(localsSize)
            .append(", ptr %sptr, ptr %sp)\n");

        int slot = 0;
        if (isInstance) {
            String tp = localPtr(0);
            body.append("  store i64 %this, ptr ").append(tp).append("\n");
            slot = 1;
        }
        boolean syncMethod = (mn.access & org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED) != 0;
        if (syncMethod) {
            declares.add("declare i32 @aot_monitor_enter(i64)");
            declares.add("declare i32 @aot_monitor_exit(i64)");
            if (isInstance) {
                syncLock = "%this";
            } else {
                syncLock = nv("lock");
                String label = ".class_lock_" + ClassParser.mangle(owner.name(), "c", "()V");
                classLocks.add(label);
                body.append("  ").append(syncLock).append(" = ptrtoint ptr @").append(label).append(" to i64\n");
            }
            body.append("  call i32 @aot_monitor_enter(i64 ").append(syncLock).append(")\n");
            syncDepth++;
        }
        for (int i = 0; i < argKinds.size(); i++) {
            String k = argKinds.get(i);
            String p = localPtr(slot);
            String llvmType = TypeInfo.llvm(k);
            String v = "%a" + i;
            switch (k) {
                case "I" -> {
                    String e = nv("e");
                    body.append("  ").append(e).append(" = sext i32 ").append(v).append(" to i64\n");
                    body.append("  store i64 ").append(e).append(", ptr ").append(p).append("\n");
                }
                case "F" -> {
                    String back = nv("e");
                    body.append("  ").append(back).append(" = bitcast i32 ").append(v).append(" to float\n");
                    String e = nv("e");
                    body.append("  ").append(e).append(" = bitcast float ").append(back).append(" to i32\n");
                    String e2 = nv("e");
                    body.append("  ").append(e2).append(" = sext i32 ").append(e).append(" to i64\n");
                    body.append("  store i64 ").append(e2).append(", ptr ").append(p).append("\n");
                }
                default -> body.append("  store ").append(llvmType).append(" ").append(v).append(", ptr ").append(p).append("\n");
            }
            slot += TypeInfo.isWide(k) ? 2 : 1;
        }

        InsnList insns = mn.instructions;
        buildHandlers();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            insnIndex = insns.indexOf(insn);
            if (terminated && !(insn instanceof LabelNode)) {
                body.append("  ").append(nv("dead")).append(":\n");
                terminated = false;
            }
            if (hasHandlers && mayThrow(insn)) {
                body.append("  store i32 ").append(insnIndex).append(", ptr %pc\n");
            }
            translateInsn(insn);
        }

        if (!terminated) {
            emitReturn(retType, "0");
        }
        if (dispatchCode != null) {
            body.append(dispatchCode);
        }
        emitHandlerEntries();
        body.append("}\n");
        return body.toString();
    }

    private Frame<BasicValue> frameHere() {
        if (frames == null || insnIndex >= frames.length) {
            return null;
        }
        return frames[insnIndex];
    }

    private String stackKindAt(int fromTop) {
        Frame<BasicValue> f = frameHere();
        if (f == null) {
            return "I";
        }
        int idx = f.getStackSize() - 1 - fromTop;
        if (idx < 0) {
            return "I";
        }
        BasicValue v = f.getStack(idx);
        return TypeInfo.kindOf(v.getType());
    }

    private void translateInsn(AbstractInsnNode insn) {
        if (insn instanceof LabelNode ln) {
            String lbl = label(ln);
            if (!terminated) {
                body.append("  br label %").append(lbl).append("\n");
            }
            body.append(lbl).append(":\n");
            terminated = false;
            return;
        }
        int op = insn.getOpcode();
        switch (op) {
            case Opcodes.NOP -> {
            }
            case Opcodes.RETURN -> {
                emitReturn("i32", "0");
                terminated = true;
            }
            case Opcodes.IRETURN -> {
                String v = popInt();
                emitReturn("i32", v);
                terminated = true;
            }
            case Opcodes.LRETURN -> {
                String v = popLong();
                emitReturn("i64", v);
                terminated = true;
            }
            case Opcodes.FRETURN -> {
                String v = popFloat();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast float ").append(v).append(" to i32\n");
                emitReturn("i32", bits);
                terminated = true;
            }
            case Opcodes.DRETURN -> {
                String v = popDouble();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast double ").append(v).append(" to i64\n");
                emitReturn("i64", bits);
                terminated = true;
            }
            case Opcodes.ARETURN -> {
                String v = popRef();
                emitReturn("i64", v);
                terminated = true;
            }
            case Opcodes.ICONST_M1 -> pushIntLit(-1);
            case Opcodes.ICONST_0 -> pushIntLit(0);
            case Opcodes.ICONST_1 -> pushIntLit(1);
            case Opcodes.ICONST_2 -> pushIntLit(2);
            case Opcodes.ICONST_3 -> pushIntLit(3);
            case Opcodes.ICONST_4 -> pushIntLit(4);
            case Opcodes.ICONST_5 -> pushIntLit(5);
            case Opcodes.LCONST_0 -> pushLongLit(0L);
            case Opcodes.LCONST_1 -> pushLongLit(1L);
            case Opcodes.FCONST_0 -> pushFloatLit(0.0f);
            case Opcodes.FCONST_1 -> pushFloatLit(1.0f);
            case Opcodes.FCONST_2 -> pushFloatLit(2.0f);
            case Opcodes.DCONST_0 -> pushDoubleLit(0.0);
            case Opcodes.DCONST_1 -> pushDoubleLit(1.0);
            case Opcodes.ACONST_NULL -> pushIntLit(0);
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> pushIntLit(((IntInsnNode) insn).operand);
            case Opcodes.LDC -> {
                LdcInsnNode n = (LdcInsnNode) insn;
                Object c = n.cst;
                if (c instanceof Integer i) {
                    pushIntLit(i);
                } else if (c instanceof Long l) {
                    pushLongLit(l);
                } else if (c instanceof Float fl) {
                    pushFloatLit(fl);
                } else if (c instanceof Double d) {
                    pushDoubleLit(d);
} else if (c instanceof String s) {
                     pushStringLit(s);
                 } else if (c instanceof org.objectweb.asm.Type t) {
                     emitClassLiteral(t);
                 } else {
                     fail("unsupported ldc constant: " + c);
                 }
            }
            case Opcodes.ILOAD -> pushIntFromLocal(((VarInsnNode) insn).var);
            case Opcodes.LLOAD -> pushLongFromLocal(((VarInsnNode) insn).var);
            case Opcodes.FLOAD -> pushFloatFromLocal(((VarInsnNode) insn).var);
            case Opcodes.DLOAD -> pushDoubleFromLocal(((VarInsnNode) insn).var);
            case Opcodes.ALOAD -> pushRefFromLocal(((VarInsnNode) insn).var);
            case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> storeLocal(((VarInsnNode) insn).var, false);
            case Opcodes.LSTORE, Opcodes.DSTORE -> storeLocal(((VarInsnNode) insn).var, true);
            case Opcodes.IINC -> {
                IincInsnNode inc = (IincInsnNode) insn;
                String p = localPtr(inc.var);
                String old = nv("iv");
                body.append("  ").append(old).append(" = load i64, ptr ").append(p).append("\n");
                String o32 = nv("iv");
                body.append("  ").append(o32).append(" = trunc i64 ").append(old).append(" to i32\n");
                String n32 = nv("iv");
                body.append("  ").append(n32).append(" = add i32 ").append(o32).append(", ").append(inc.incr).append("\n");
                String n64 = nv("iv");
                body.append("  ").append(n64).append(" = sext i32 ").append(n32).append(" to i64\n");
                body.append("  store i64 ").append(n64).append(", ptr ").append(p).append("\n");
            }
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                 Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> intArith(op);
            case Opcodes.IDIV, Opcodes.IREM -> intDivRem(op);
            case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
                 Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> longArith(op);
            case Opcodes.LDIV, Opcodes.LREM -> longDivRem(op);
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> floatArith(op);
            case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> doubleArith(op);
            case Opcodes.INEG -> {
                String a = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = sub i32 0, ").append(a).append("\n");
                pushInt(r);
            }
            case Opcodes.LNEG -> {
                String a = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = sub i64 0, ").append(a).append("\n");
                pushLong(r);
            }
            case Opcodes.FNEG -> {
                String a = popFloat();
                String r = nv("v");
                body.append("  ").append(r).append(" = fneg float ").append(a).append("\n");
                pushFloat(r);
            }
            case Opcodes.DNEG -> {
                String a = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = fneg double ").append(a).append("\n");
                pushDouble(r);
            }
            case Opcodes.I2L -> {
                String a = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = sext i32 ").append(a).append(" to i64\n");
                pushLong(r);
            }
            case Opcodes.I2F -> {
                String a = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = sitofp i32 ").append(a).append(" to float\n");
                pushFloat(r);
            }
            case Opcodes.I2D -> {
                String a = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = sitofp i32 ").append(a).append(" to double\n");
                pushDouble(r);
            }
            case Opcodes.L2I -> {
                String a = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = trunc i64 ").append(a).append(" to i32\n");
                pushInt(r);
            }
            case Opcodes.L2F -> {
                String a = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = sitofp i64 ").append(a).append(" to float\n");
                pushFloat(r);
            }
            case Opcodes.L2D -> {
                String a = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = sitofp i64 ").append(a).append(" to double\n");
                pushDouble(r);
}
            case Opcodes.F2I -> {
                String a = popFloat();
                pushInt(f2i32(a, false));
            }
            case Opcodes.F2L -> {
                String a = popFloat();
                pushLong(f2i64(a, false));
            }
            case Opcodes.F2D -> {
                String a = popFloat();
                String r = nv("v");
                body.append("  ").append(r).append(" = fpext float ").append(a).append(" to double\n");
                pushDouble(r);
            }
            case Opcodes.D2I -> {
                String a = popDouble();
                pushInt(f2i32(a, true));
            }
            case Opcodes.D2L -> {
                String a = popDouble();
                pushLong(f2i64(a, true));
            }
            case Opcodes.D2F -> {
                String a = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = fptrunc double ").append(a).append(" to float\n");
                pushFloat(r);
            }
            case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> {
                String a = popInt();
                pushInt(a);
            }
            case Opcodes.LCMP -> {
                String b = popLong();
                String a = popLong();
                String c1 = nv("c");
                body.append("  ").append(c1).append(" = icmp slt i64 ").append(a).append(", ").append(b).append("\n");
                String c2 = nv("c");
                body.append("  ").append(c2).append(" = icmp sgt i64 ").append(a).append(", ").append(b).append("\n");
                String m = nv("m");
                body.append("  ").append(m).append(" = select i1 ").append(c2).append(", i32 1, i32 0\n");
                String z = nv("z");
                body.append("  ").append(z).append(" = select i1 ").append(c1).append(", i32 -1, i32 ").append(m).append("\n");
                pushInt(z);
            }
            case Opcodes.FCMPL, Opcodes.FCMPG -> {
                String b = popFloat();
                String a = popFloat();
                pushInt(floatCompare(a, b, op == Opcodes.FCMPG ? 1.0f : -1.0f));
            }
            case Opcodes.DCMPL, Opcodes.DCMPG -> {
                String b = popDouble();
                String a = popDouble();
                pushInt(doubleCompare(a, b, op == Opcodes.DCMPG ? 1.0 : -1.0));
            }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> ifSingle(op, (JumpInsnNode) insn);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> ifCompare(op, (JumpInsnNode) insn);
            case Opcodes.IFNULL -> ifRefNull(true, (JumpInsnNode) insn);
            case Opcodes.IFNONNULL -> ifRefNull(false, (JumpInsnNode) insn);
            case Opcodes.IF_ACMPEQ -> ifRefCompare(true, (JumpInsnNode) insn);
            case Opcodes.IF_ACMPNE -> ifRefCompare(false, (JumpInsnNode) insn);
            case Opcodes.GOTO -> {
                LabelNode target = ((JumpInsnNode) insn).label;
                body.append("  br label %").append(label(target)).append("\n");
                terminated = true;
            }
            case Opcodes.POP -> pop();
            case Opcodes.POP2 -> {
                String k = stackKindAt(0);
                if (TypeInfo.isWide(k)) {
                    pop();
                } else {
                    pop();
                    pop();
                }
            }
            case Opcodes.DUP -> {
                Slot s = popSlot();
                pushSlot(s);
                pushSlot(s);
            }
            case Opcodes.DUP2 -> {
                String k = stackKindAt(0);
                if (TypeInfo.isWide(k)) {
                    Slot s = popSlot();
                    pushSlot(s);
                    pushSlot(s);
                } else {
                    Slot b = popSlot();
                    Slot a = popSlot();
                    pushSlot(a);
                    pushSlot(b);
                    pushSlot(a);
                    pushSlot(b);
                }
            }
            case Opcodes.DUP_X1 -> {
                Slot b = popSlot();
                Slot a = popSlot();
                pushSlot(b);
                pushSlot(a);
                pushSlot(b);
            }
            case Opcodes.DUP_X2 -> {
                String k0 = stackKindAt(0);
                String k1 = stackKindAt(1);
                if (TypeInfo.isWide(k1)) {
                    Slot b = popSlot();
                    Slot a = popSlot();
                    pushSlot(b);
                    pushSlot(a);
                    pushSlot(b);
                } else {
                    Slot c = popSlot();
                    Slot b = popSlot();
                    Slot a = popSlot();
                    pushSlot(c);
                    pushSlot(a);
                    pushSlot(b);
                    pushSlot(c);
                }
            }
            case Opcodes.SWAP -> {
                Slot b = popSlot();
                Slot a = popSlot();
                pushSlot(b);
                pushSlot(a);
            }
            case Opcodes.ATHROW -> aThrow();
            case Opcodes.MONITORENTER -> {
                String obj = popRef();
                body.append("  call i32 @aot_monitor_enter(i64 ").append(obj).append(")\n");
            }
            case Opcodes.MONITOREXIT -> {
                String obj = popRef();
                body.append("  call i32 @aot_monitor_exit(i64 ").append(obj).append(")\n");
            }
            case Opcodes.CHECKCAST -> checkCast((TypeInsnNode) insn);
            case Opcodes.INSTANCEOF -> instanceOf((TypeInsnNode) insn);
            case Opcodes.NEW -> newObject((TypeInsnNode) insn);
            case Opcodes.NEWARRAY -> newPrimArray(((IntInsnNode) insn).operand);
            case Opcodes.ANEWARRAY -> newRefArray(((TypeInsnNode) insn).desc);
            case Opcodes.ARRAYLENGTH -> {
                String av = popRef();
                emitNullCheck(av);
                String ap = toPtr(av);
                String lenp = nv("al");
                body.append("  ").append(lenp).append(" = getelementptr i8, ptr ").append(ap).append(", i32 ").append(ARRAY_LEN_OFFSET).append("\n");
                String len = nv("al");
                body.append("  ").append(len).append(" = load i32, ptr ").append(lenp).append("\n");
                pushInt(len);
            }
            case Opcodes.IALOAD -> arrayLoadI32();
            case Opcodes.BALOAD -> arrayLoadI8();
            case Opcodes.CALOAD -> arrayLoadChar();
            case Opcodes.SALOAD -> arrayLoadShort();
            case Opcodes.LALOAD -> arrayLoadI64();
            case Opcodes.FALOAD -> arrayLoadF32();
            case Opcodes.DALOAD -> arrayLoadI64();
            case Opcodes.AALOAD -> arrayLoadI64();
            case Opcodes.IASTORE -> arrayStoreI32();
            case Opcodes.BASTORE -> arrayStoreI8();
            case Opcodes.CASTORE -> arrayStoreI16();
            case Opcodes.SASTORE -> arrayStoreI16();
            case Opcodes.LASTORE, Opcodes.DASTORE, Opcodes.AASTORE -> arrayStoreI64();
            case Opcodes.FASTORE -> arrayStoreF32();
            case Opcodes.GETFIELD -> getField((FieldInsnNode) insn);
            case Opcodes.PUTFIELD -> putField((FieldInsnNode) insn);
            case Opcodes.INVOKEDYNAMIC -> invokeDynamic((InvokeDynamicInsnNode) insn);
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (Natives.isSystemOut(f.owner, f.name)) {
                    pushIntLit(0);
                } else if (isPrimitiveTypeField(f.owner, f.name)) {
                    declares.add("declare i64 @aot_class_of_type(i32)");
                    String r = nv("v");
                    body.append("  ").append(r).append(" = call i64 @aot_class_of_type(i32 ")
                        .append(layout.typeId(primitiveDesc(f.owner))).append(")\n");
                    pushRef(r);
                } else {
                    String sv = nv("sv");
                    body.append("  ").append(sv).append(" = load i64, ptr ").append(staticLabel(f)).append("\n");
                    pushLong(sv);
                }
            }
            case Opcodes.PUTSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                String sv = pop();
                body.append("  store i64 ").append(sv).append(", ptr ").append(staticLabel(f)).append("\n");
            }
            case Opcodes.INVOKESTATIC -> invoke((MethodInsnNode) insn);
            case Opcodes.INVOKESPECIAL -> invokeSpecial((MethodInsnNode) insn);
            case Opcodes.INVOKEVIRTUAL -> invokeVirtual((MethodInsnNode) insn);
            case Opcodes.INVOKEINTERFACE -> invokeVirtual((MethodInsnNode) insn);
            default -> fail("unsupported opcode " + op + " in " + owner.name() + "." + mn.name);
        }
    }

    private String staticLabel(FieldInsnNode f) {
        return layout.staticLabel(f);
    }

    private boolean mayThrow(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.INVOKESTATIC
            || op == Opcodes.INVOKEVIRTUAL
            || op == Opcodes.INVOKESPECIAL
            || op == Opcodes.INVOKEINTERFACE
            || op == Opcodes.NEW
            || op == Opcodes.NEWARRAY
            || op == Opcodes.ANEWARRAY
            || op == Opcodes.ATHROW
            || op == Opcodes.CHECKCAST
            || op == Opcodes.IDIV
            || op == Opcodes.IREM
            || op == Opcodes.LDIV
            || op == Opcodes.LREM
            || op == Opcodes.GETFIELD
            || op == Opcodes.PUTFIELD
            || op == Opcodes.GETSTATIC
            || op == Opcodes.PUTSTATIC
            || op == Opcodes.ARRAYLENGTH
            || op == Opcodes.IALOAD
            || op == Opcodes.LALOAD
            || op == Opcodes.FALOAD
            || op == Opcodes.DALOAD
            || op == Opcodes.AALOAD
            || op == Opcodes.BALOAD
            || op == Opcodes.CALOAD
            || op == Opcodes.SALOAD
            || op == Opcodes.IASTORE
            || op == Opcodes.LASTORE
            || op == Opcodes.FASTORE
            || op == Opcodes.DASTORE
            || op == Opcodes.AASTORE
            || op == Opcodes.BASTORE
            || op == Opcodes.CASTORE
            || op == Opcodes.SASTORE;
    }

    private void aThrow() {
        String ev = popRef();
        body.append("  store i64 ").append(ev).append(", ptr @.pending_exc\n");
        emitUnwind();
    }

    private void buildHandlers() {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) {
            return;
        }
        hasHandlers = true;
        handlerLabels.clear();
        String dispatch = "excd" + (tmp++);
        for (int i = 0; i < mn.tryCatchBlocks.size(); i++) {
            handlerLabels.add("exch" + (tmp++));
        }
        handlerDispatch = dispatch;
        StringBuilder out = new StringBuilder();
        out.append(dispatch).append(":\n");
        String pcv = nv("pc");
        out.append("  ").append(pcv).append(" = load i32, ptr %pc\n");
        String pe = nv("pe");
        out.append("  ").append(pe).append(" = load i64, ptr @.pending_exc\n");
        String op = nv("pe");
        out.append("  ").append(op).append(" = inttoptr i64 ").append(pe).append(" to ptr\n");
        String tp = nv("pe");
        out.append("  ").append(tp).append(" = getelementptr i8, ptr ").append(op).append(", i32 8\n");
        String tid = nv("pe");
        out.append("  ").append(tid).append(" = load i32, ptr ").append(tp).append("\n");
        for (int i = 0; i < mn.tryCatchBlocks.size(); i++) {
            org.objectweb.asm.tree.TryCatchBlockNode tcb = mn.tryCatchBlocks.get(i);
            int lo = mn.instructions.indexOf(tcb.start);
            int hi = mn.instructions.indexOf(tcb.end);
            String geLo = nv("pe");
            out.append("  ").append(geLo).append(" = icmp sge i32 ").append(pcv).append(", ").append(lo).append("\n");
            String ltHi = nv("pe");
            out.append("  ").append(ltHi).append(" = icmp slt i32 ").append(pcv).append(", ").append(hi).append("\n");
            String inRange = nv("pe");
            out.append("  ").append(inRange).append(" = and i1 ").append(geLo).append(", ").append(ltHi).append("\n");
            String typeOk;
            if (tcb.type == null) {
                typeOk = null;
            } else {
                String idx = typeIndex(tcb.type);
                String base = nv("pe");
                out.append("  ").append(base).append(" = getelementptr [").append(typeTableSlots())
                    .append(" x i32], ptr ").append(idx).append(", i64 0, i32 0\n");
                String cell = nv("pe");
                out.append("  ").append(cell).append(" = getelementptr i32, ptr ").append(base).append(", i32 ").append(tid).append("\n");
                String ok = nv("pe");
                out.append("  ").append(ok).append(" = load i32, ptr ").append(cell).append("\n");
                String nzc = nv("pe");
                out.append("  ").append(nzc).append(" = icmp ne i32 ").append(ok).append(", 0\n");
                typeOk = nzc;
            }
            String hit;
            if (typeOk == null) {
                hit = inRange;
            } else {
                hit = nv("pe");
                out.append("  ").append(hit).append(" = and i1 ").append(inRange).append(", ").append(typeOk).append("\n");
            }
            String next = "exdn" + (tmp++);
            out.append("  br i1 ").append(hit).append(", label %").append(handlerLabels.get(i))
                .append(", label %").append(next).append("\n");
            out.append(next).append(":\n");
        }
        out.append("  ret i32 0\n");
        dispatchCode = out.toString();
    }

    private void emitHandlerEntries() {
        if (handlerLabels.isEmpty()) {
            return;
        }
        for (int i = 0; i < mn.tryCatchBlocks.size(); i++) {
            org.objectweb.asm.tree.TryCatchBlockNode tcb = mn.tryCatchBlocks.get(i);
            body.append(handlerLabels.get(i)).append(":\n");
            String pe = nv("pe");
            body.append("  ").append(pe).append(" = load i64, ptr @.pending_exc\n");
            body.append("  store i64 0, ptr @.pending_exc\n");
            String sp = nv("pe");
            body.append("  ").append(sp).append(" = load i32, ptr %sp\n");
            String cz = nv("pe");
            body.append("  ").append(cz).append(" = icmp sgt i32 ").append(sp).append(", 0\n");
            String zbr = "excz" + (tmp++);
            String zdone = "exczd" + (tmp++);
            body.append("  br i1 ").append(cz).append(", label %").append(zbr).append(", label %").append(zdone).append("\n");
            body.append(zbr).append(":\n  store i32 0, ptr %sp\n  br label %").append(zdone).append("\n");
            body.append(zdone).append(":\n");
            stack.clear();
            pushRef(pe);
            String target = label(tcb.handler);
            body.append("  br label %").append(target).append("\n");
        }
    }

    private void emitUnwind() {
        if (handlerDispatch != null) {
            body.append("  br label %").append(handlerDispatch).append("\n");
            terminated = true;
            return;
        }
        emitReturn(methodRetType(), "0");
        terminated = true;
    }

    public static Set<String> classLockNames() {
        return classLocks;
    }

    private String methodRetType() {
        String k = TypeInfo.returnKind(mn.desc);
        return "V".equals(k) ? "i32" : TypeInfo.llvm(k);
    }

    private void emitReturn(String type, String value) {
        String slot = "%ret";
        body.append("  store ").append(type).append(" ").append(value).append(", ptr ").append(slot).append("\n");
        if (syncDepth > 0) {
            body.append("  call i32 @aot_monitor_exit(i64 ").append(syncLock).append(")\n");
        }
        body.append("  call void @gc_frame_pop(ptr %frame)\n");
        String v = nv("rv");
        body.append("  ").append(v).append(" = load ").append(type).append(", ptr ").append(slot).append("\n");
        body.append("  ret ").append(type).append(" ").append(v).append("\n");
    }

private String currentHandler() {
        return handlerDispatch;
    }

    private void emitCallUnwind() {
        String lbl = "excchk" + (tmp++);
        String ok = "excok" + (tmp++);
        String pend = nv("pe");
        body.append("  ").append(pend).append(" = load i64, ptr @.pending_exc\n");
        String nz = nv("pe");
        body.append("  ").append(nz).append(" = icmp ne i64 ").append(pend).append(", 0\n");
        body.append("  br i1 ").append(nz).append(", label %").append(lbl).append(", label %").append(ok).append("\n");
        body.append(lbl).append(":\n");
        emitUnwind();
        body.append(ok).append(":\n");
        terminated = false;
    }

    private String f2i32(String val, boolean isDouble) {
        String ty = isDouble ? "double" : "float";
        
        String nan = nv("c");
        body.append("  ").append(nan).append(" = fcmp uno ").append(ty).append(" ").append(val).append(", ").append(val).append("\n");
        String hi = nv("c");
        if (isDouble) {
            body.append("  ").append(hi).append(" = fcmp ogt double ").append(val).append(", 2147483647.0\n");
        } else {
            body.append("  ").append(hi).append(" = fcmp oge float ").append(val).append(", 0x41E0000000000000\n");
        }
        String lo = nv("c");
        if (isDouble) {
            body.append("  ").append(lo).append(" = fcmp olt double ").append(val).append(", -2147483648.0\n");
        } else {
            body.append("  ").append(lo).append(" = fcmp olt float ").append(val).append(", 0xC1E0000000000000\n");
        }
        String cv = nv("c");
        body.append("  ").append(cv).append(" = fptosi ").append(ty).append(" ").append(val).append(" to i32\n");
        String c1 = nv("c");
        body.append("  ").append(c1).append(" = select i1 ").append(hi).append(", i32 2147483647, i32 ").append(cv).append("\n");
        String c2 = nv("c");
        body.append("  ").append(c2).append(" = select i1 ").append(lo).append(", i32 -2147483648, i32 ").append(c1).append("\n");
        String res = nv("c");
        body.append("  ").append(res).append(" = select i1 ").append(nan).append(", i32 0, i32 ").append(c2).append("\n");
        return res;
    }

    private String f2i64(String val, boolean isDouble) {
        String ty = isDouble ? "double" : "float";
        String nan = nv("c");
        body.append("  ").append(nan).append(" = fcmp uno ").append(ty).append(" ").append(val).append(", ").append(val).append("\n");
        String hi = nv("c");
        String lo = nv("c");
        if (isDouble) {
            body.append("  ").append(hi).append(" = fcmp oge double ").append(val).append(", 0x43E0000000000000\n");
            body.append("  ").append(lo).append(" = fcmp olt double ").append(val).append(", 0xC3E0000000000000\n");
        } else {
            body.append("  ").append(hi).append(" = fcmp oge float ").append(val).append(", 0x43E0000000000000\n");
            body.append("  ").append(lo).append(" = fcmp olt float ").append(val).append(", 0xC3E0000000000000\n");
        }
        String cv = nv("c");
        body.append("  ").append(cv).append(" = fptosi ").append(ty).append(" ").append(val).append(" to i64\n");
        String c1 = nv("c");
        body.append("  ").append(c1).append(" = select i1 ").append(hi).append(", i64 9223372036854775807, i64 ").append(cv).append("\n");
        String c2 = nv("c");
        body.append("  ").append(c2).append(" = select i1 ").append(lo).append(", i64 -9223372036854775808, i64 ").append(c1).append("\n");
        String res = nv("c");
        body.append("  ").append(res).append(" = select i1 ").append(nan).append(", i64 0, i64 ").append(c2).append("\n");
        return res;
    }

    private String intArithLlvm(int op) {
        return switch (op) {
            case Opcodes.IADD -> "add";
            case Opcodes.ISUB -> "sub";
            case Opcodes.IMUL -> "mul";
            case Opcodes.IAND -> "and";
            case Opcodes.IOR -> "or";
            case Opcodes.IXOR -> "xor";
            case Opcodes.ISHL -> "shl";
            case Opcodes.ISHR -> "ashr";
            default -> "lshr";
        };
    }

    private void intArith(int op) {
        String b = popInt();
        String a = popInt();
        String sh;
        if (op == Opcodes.ISHL || op == Opcodes.ISHR || op == Opcodes.IUSHR) {
            String m = nv("v");
            body.append("  ").append(m).append(" = and i32 ").append(b).append(", 31\n");
            sh = m;
        } else {
            sh = b;
        }
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(intArithLlvm(op)).append(" i32 ").append(a).append(", ").append(sh).append("\n");
        pushInt(r);
    }

    private void intDivRem(int op) {
        String b = popInt();
        String a = popInt();
        String zc = nv("c");
        body.append("  ").append(zc).append(" = icmp eq i32 ").append(b).append(", 0\n");
        String bb = nv("b");
        body.append("  ").append(bb).append(" = select i1 ").append(zc).append(", i32 1, i32 ").append(b).append("\n");
        String minc = nv("c");
        body.append("  ").append(minc).append(" = icmp eq i32 ").append(a).append(", -2147483648\n");
        String negc = nv("c");
        body.append("  ").append(negc).append(" = icmp eq i32 ").append(bb).append(", -1\n");
        String ovf = nv("c");
        body.append("  ").append(ovf).append(" = and i1 ").append(minc).append(", ").append(negc).append("\n");
        String aadj = nv("b");
        body.append("  ").append(aadj).append(" = select i1 ").append(ovf).append(", i32 -2147483647, i32 ").append(a).append("\n");
        String raw = nv("v");
        body.append("  ").append(raw).append(" = ").append(op == Opcodes.IDIV ? "sdiv" : "srem")
            .append(" i32 ").append(aadj).append(", ").append(bb).append("\n");
        String ovfQuo = nv("v");
        body.append("  ").append(ovfQuo).append(" = select i1 ").append(ovf).append(", i32 -2147483648, i32 ").append(raw).append("\n");
        String ovfRem = nv("v");
        body.append("  ").append(ovfRem).append(" = select i1 ").append(ovf).append(", i32 0, i32 ").append(raw).append("\n");
        pushInt(op == Opcodes.IDIV ? ovfQuo : ovfRem);
    }

    private String longArithLlvm(int op) {
        return switch (op) {
            case Opcodes.LADD -> "add";
            case Opcodes.LSUB -> "sub";
            case Opcodes.LMUL -> "mul";
            case Opcodes.LAND -> "and";
            case Opcodes.LOR -> "or";
            case Opcodes.LXOR -> "xor";
            case Opcodes.LSHL -> "shl";
            case Opcodes.LSHR -> "ashr";
            default -> "lshr";
        };
    }

    private void longArith(int op) {
        if (op == Opcodes.LSHL || op == Opcodes.LSHR || op == Opcodes.LUSHR) {
            String sh = popInt();
            String a = popLong();
            String m = nv("s");
            body.append("  ").append(m).append(" = and i32 ").append(sh).append(", 63\n");
            String s64 = nv("s");
            body.append("  ").append(s64).append(" = zext i32 ").append(m).append(" to i64\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = ").append(longArithLlvm(op)).append(" i64 ").append(a).append(", ").append(s64).append("\n");
            pushLong(r);
            return;
        }
        String b = popLong();
        String a = popLong();
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(longArithLlvm(op)).append(" i64 ").append(a).append(", ").append(b).append("\n");
        pushLong(r);
    }

    private void longDivRem(int op) {
        String b = popLong();
        String a = popLong();
        String zc = nv("c");
        body.append("  ").append(zc).append(" = icmp eq i64 ").append(b).append(", 0\n");
        String bb = nv("b");
        body.append("  ").append(bb).append(" = select i1 ").append(zc).append(", i64 1, i64 ").append(b).append("\n");
        String minc = nv("c");
        body.append("  ").append(minc).append(" = icmp eq i64 ").append(a).append(", -9223372036854775808\n");
        String negc = nv("c");
        body.append("  ").append(negc).append(" = icmp eq i64 ").append(bb).append(", -1\n");
        String ovf = nv("c");
        body.append("  ").append(ovf).append(" = and i1 ").append(minc).append(", ").append(negc).append("\n");
        String aadj = nv("b");
        body.append("  ").append(aadj).append(" = select i1 ").append(ovf).append(", i64 -9223372036854775807, i64 ").append(a).append("\n");
        String raw = nv("v");
        body.append("  ").append(raw).append(" = ").append(op == Opcodes.LDIV ? "sdiv" : "srem")
            .append(" i64 ").append(aadj).append(", ").append(bb).append("\n");
        String ovfQuo = nv("v");
        body.append("  ").append(ovfQuo).append(" = select i1 ").append(ovf).append(", i64 -9223372036854775808, i64 ").append(raw).append("\n");
        String ovfRem = nv("v");
        body.append("  ").append(ovfRem).append(" = select i1 ").append(ovf).append(", i64 0, i64 ").append(raw).append("\n");
        pushLong(op == Opcodes.LDIV ? ovfQuo : ovfRem);
    }

    private String floatArithLlvm(int op) {
        return switch (op) {
            case Opcodes.FADD -> "fadd";
            case Opcodes.FSUB -> "fsub";
            case Opcodes.FMUL -> "fmul";
            case Opcodes.FDIV -> "fdiv";
            default -> "frem";
        };
    }

    private void floatArith(int op) {
        String b = popFloat();
        String a = popFloat();
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(floatArithLlvm(op)).append(" float ").append(a).append(", ").append(b).append("\n");
        pushFloat(r);
    }

    private String doubleArithLlvm(int op) {
        return switch (op) {
            case Opcodes.DADD -> "fadd";
            case Opcodes.DSUB -> "fsub";
            case Opcodes.DMUL -> "fmul";
            case Opcodes.DDIV -> "fdiv";
            default -> "frem";
        };
    }

    private void doubleArith(int op) {
        String b = popDouble();
        String a = popDouble();
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(doubleArithLlvm(op)).append(" double ").append(a).append(", ").append(b).append("\n");
        pushDouble(r);
    }

    private String floatCompare(String a, String b, float nanResult) {
        String o1 = nv("c");
        body.append("  ").append(o1).append(" = fcmp ogt float ").append(a).append(", ").append(b).append("\n");
        String o2 = nv("c");
        body.append("  ").append(o2).append(" = fcmp oeq float ").append(a).append(", ").append(b).append("\n");
        String m = nv("m");
        body.append("  ").append(m).append(" = select i1 ").append(o2).append(", i32 0, i32 -1\n");
        String nan = nv("n");
        body.append("  ").append(nan).append(" = fcmp uno float ").append(a).append(", ").append(b).append("\n");
        String base = nv("b");
        body.append("  ").append(base).append(" = select i1 ").append(o1).append(", i32 1, i32 ").append(m).append("\n");
        String r = nv("r");
        body.append("  ").append(r).append(" = select i1 ").append(nan).append(", i32 ").append((int) nanResult).append(", i32 ").append(base).append("\n");
        return r;
    }

    private String doubleCompare(String a, String b, double nanResult) {
        String o1 = nv("c");
        body.append("  ").append(o1).append(" = fcmp ogt double ").append(a).append(", ").append(b).append("\n");
        String o2 = nv("c");
        body.append("  ").append(o2).append(" = fcmp oeq double ").append(a).append(", ").append(b).append("\n");
        String m = nv("m");
        body.append("  ").append(m).append(" = select i1 ").append(o2).append(", i32 0, i32 -1\n");
        String nan = nv("n");
        body.append("  ").append(nan).append(" = fcmp uno double ").append(a).append(", ").append(b).append("\n");
        String base = nv("b");
        body.append("  ").append(base).append(" = select i1 ").append(o1).append(", i32 1, i32 ").append(m).append("\n");
        String r = nv("r");
        body.append("  ").append(r).append(" = select i1 ").append(nan).append(", i32 ").append((int) nanResult).append(", i32 ").append(base).append("\n");
        return r;
    }

    private void checkCast(TypeInsnNode tn) {
        Slot s = popSlot();
        String target = tn.desc;
        if (!layout.knownType(target)) {
            pushSlot(s);
            return;
        }
        String nullc = nv("cv");
        body.append("  ").append(nullc).append(" = icmp eq i64 ").append(s.value).append(", 0\n");
        String nullBr = "cvnull" + (tmp++);
        String doBr = "cvdo" + (tmp++);
        String badBr = "cvbad" + (tmp++);
        String join = "cvjoin" + (tmp++);
        body.append("  br i1 ").append(nullc).append(", label %").append(nullBr)
            .append(", label %").append(doBr).append("\n");
        body.append(nullBr).append(":\n  br label %").append(join).append("\n");
        body.append(doBr).append(":\n");
        String objp = toPtr(s.value);
        String tip = nv("cv");
        body.append("  ").append(tip).append(" = getelementptr i8, ptr ").append(objp).append(", i32 8\n");
        String tid = nv("cv");
        body.append("  ").append(tid).append(" = load i32, ptr ").append(tip).append("\n");
        String idx = typeIndex(target);
        String base = nv("cv");
        body.append("  ").append(base).append(" = getelementptr [").append(typeTableSlots()).append(" x i32], ptr ")
            .append(idx).append(", i64 0, i32 0\n");
        String cell = nv("cv");
        body.append("  ").append(cell).append(" = getelementptr i32, ptr ").append(base).append(", i32 ").append(tid).append("\n");
        String ok = nv("cv");
        body.append("  ").append(ok).append(" = load i32, ptr ").append(cell).append("\n");
        String nzc = nv("cv");
        body.append("  ").append(nzc).append(" = icmp ne i32 ").append(ok).append(", 0\n");
        body.append("  br i1 ").append(nzc).append(", label %").append(join)
            .append(", label %").append(badBr).append("\n");
        body.append(badBr).append(":\n  br label %").append(join).append("\n");
        body.append(join).append(":\n");
        String ph = nv("cv");
        body.append("  ").append(ph).append(" = phi i64 [ 0, %").append(nullBr).append(" ], [ ").append(s.value)
            .append(", %").append(doBr).append(" ], [ 0, %").append(badBr).append(" ]\n");
        pushRef(ph);
    }

    private void instanceOf(TypeInsnNode tn) {
        Slot s = popSlot();
        String target = tn.desc;
        if (!layout.knownType(target)) {
            pushIntLit(0);
            return;
        }
        String nullc = nv("iv");
        body.append("  ").append(nullc).append(" = icmp eq i64 ").append(s.value).append(", 0\n");
        String nullBr = "ivnull" + (tmp++);
        String nullOut = "ivinullres" + (tmp++);
        String doBr = "ivdo" + (tmp++);
        String join = "ivjoin" + (tmp++);
        body.append("  br i1 ").append(nullc).append(", label %").append(nullBr)
            .append(", label %").append(doBr).append("\n");
        body.append(nullBr).append(":\n  br label %").append(join).append("\n");
        body.append(doBr).append(":\n");
        String objp = toPtr(s.value);
        String tip = nv("iv");
        body.append("  ").append(tip).append(" = getelementptr i8, ptr ").append(objp).append(", i32 8\n");
        String tid = nv("iv");
        body.append("  ").append(tid).append(" = load i32, ptr ").append(tip).append("\n");
        String idx = typeIndex(target);
        String base = nv("iv");
        body.append("  ").append(base).append(" = getelementptr [").append(typeTableSlots()).append(" x i32], ptr ")
            .append(idx).append(", i64 0, i32 0\n");
        String cell = nv("iv");
        body.append("  ").append(cell).append(" = getelementptr i32, ptr ").append(base).append(", i32 ").append(tid).append("\n");
        String ok = nv("iv");
        body.append("  ").append(ok).append(" = load i32, ptr ").append(cell).append("\n");
        String nzc = nv("iv");
        body.append("  ").append(nzc).append(" = icmp ne i32 ").append(ok).append(", 0\n");
        String res = nv("iv");
        body.append("  ").append(res).append(" = zext i1 ").append(nzc).append(" to i32\n");
        body.append("  br label %").append(join).append("\n");
        body.append(join).append(":\n");
        String ph = nv("iv");
        body.append("  ").append(ph).append(" = phi i32 [ 0, %").append(nullBr).append(" ], [ ").append(res)
            .append(", %").append(doBr).append(" ]\n");
        pushInt(ph);
    }


    private String typeIndex(String cls) {
        return layout.assignTable(cls);
    }

    private int typeTableSlots() {
        return layout.typeTableSlots();
    }

    private static boolean isPrimitiveTypeField(String owner, String name) {
        if (!"TYPE".equals(name)) {
            return false;
        }
        return primitiveDesc(owner) != null;
    }

    private static String primitiveDesc(String owner) {
        return switch (owner) {
            case "java/lang/Integer" -> "I";
            case "java/lang/Long" -> "J";
            case "java/lang/Short" -> "S";
            case "java/lang/Byte" -> "B";
            case "java/lang/Character" -> "C";
            case "java/lang/Boolean" -> "Z";
            case "java/lang/Float" -> "F";
            case "java/lang/Double" -> "D";
            case "java/lang/Void" -> "V";
            default -> null;
        };
    }

    private void newObject(TypeInsnNode tn) {
        String cls = tn.desc;
        if ("java/lang/StringBuilder".equals(cls) || "java/lang/StringBuffer".equals(cls)) {
            String r = nv("np");
            body.append("  ").append(r).append(" = call i64 @aot_sb_new()\n");
            pushRef(r);
            return;
        }
        if ("java/util/ArrayList".equals(cls) || "java/util/LinkedList".equals(cls)
                || "java/util/Vector".equals(cls) || "java/util/HashSet".equals(cls)) {
            String r = nv("np");
            body.append("  ").append(r).append(" = call i64 @aot_list_new()\n");
            pushRef(r);
            return;
        }
        if ("java/util/HashMap".equals(cls) || "java/util/LinkedHashMap".equals(cls)
                || "java/util/TreeMap".equals(cls) || "java/util/Hashtable".equals(cls)) {
            String r = nv("np");
            body.append("  ").append(r).append(" = call i64 @aot_map_new()\n");
            pushRef(r);
            return;
        }
        if ("java/lang/Thread".equals(cls)) {
            String r = nv("np");
            body.append("  ").append(r).append(" = call i64 @aot_thread_rec_new()\n");
            pushRef(r);
            return;
        }
        if (!layout.hasClass(cls)) {
            fail("unknown class for new: " + cls);
        }
        declares.add("declare ptr @gc_calloc(i64, i64)");
        int size = layout.objectSize(cls);
        String raw = nv("np");
        body.append("  ").append(raw).append(" = call ptr @gc_calloc(i64 ").append(size).append(", i64 1)\n");
        body.append("  store ptr ").append(layout.vtableLabel(cls)).append(", ptr ").append(raw).append("\n");
        String tip = nv("np");
        body.append("  ").append(tip).append(" = getelementptr i8, ptr ").append(raw).append(", i32 8\n");
        body.append("  store i32 ").append(layout.typeId(cls)).append(", ptr ").append(tip).append("\n");
        String iv = nv("nv");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(raw).append(" to i64\n");
        pushRef(iv);
    }

    private static final int ARRAY_LEN_OFFSET = 16;
    private static final int ARRAY_DATA_OFFSET = 24;

    private void newArray(String elemKind, String typeDesc) {
        String cnt = popInt();
        declares.add("declare ptr @gc_calloc(i64, i64)");
        String neg = nv("an");
        body.append("  ").append(neg).append(" = icmp slt i32 ").append(cnt).append(", 0\n");
        String negBr = "ane" + (tmp++);
        String okBr = "anok" + (tmp++);
        body.append("  br i1 ").append(neg).append(", label %").append(negBr).append(", label %").append(okBr).append("\n");
        body.append(negBr).append(":\n");
        throwBuiltin("java/lang/NegativeArraySizeException");
        body.append(okBr).append(":\n");
        terminated = false;
        String c64 = nv("an");
        body.append("  ").append(c64).append(" = sext i32 ").append(cnt).append(" to i64\n");
        String esz = String.valueOf(elemSize(elemKind));
        String bytes = nv("ab");
        body.append("  ").append(bytes).append(" = mul i64 ").append(c64).append(", ").append(esz).append("\n");
        String total = nv("ab");
        body.append("  ").append(total).append(" = add i64 ").append(bytes).append(", ").append(ARRAY_DATA_OFFSET).append("\n");
        String raw = nv("ap");
        body.append("  ").append(raw).append(" = call ptr @gc_calloc(i64 ").append(total).append(", i64 1)\n");
        body.append("  store ptr @.vt_empty, ptr ").append(raw).append("\n");
        String tidp = nv("ap");
        body.append("  ").append(tidp).append(" = getelementptr i8, ptr ").append(raw).append(", i32 8\n");
        body.append("  store i32 ").append(layout.typeId(typeDesc)).append(", ptr ").append(tidp).append("\n");
        String lenp = nv("ap");
        body.append("  ").append(lenp).append(" = getelementptr i8, ptr ").append(raw).append(", i32 ").append(ARRAY_LEN_OFFSET).append("\n");
        body.append("  store i32 ").append(cnt).append(", ptr ").append(lenp).append("\n");
        String iv = nv("av");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(raw).append(" to i64\n");
        pushRef(iv);
    }

    private static int elemSize(String kind) {
        return 8;
    }

    private String arrayElemPtr(String kind) {
        String idx = popInt();
        String av = popRef();
        emitNullCheck(av);
        String ap = toPtr(av);
        String lenp = nv("ap");
        body.append("  ").append(lenp).append(" = getelementptr i8, ptr ").append(ap).append(", i32 ").append(ARRAY_LEN_OFFSET).append("\n");
        String len = nv("ap");
        body.append("  ").append(len).append(" = load i32, ptr ").append(lenp).append("\n");
        String oob = nv("ap");
        body.append("  ").append(oob).append(" = icmp uge i32 ").append(idx).append(", ").append(len).append("\n");
        String oobBr = "aoe" + (tmp++);
        String okBr = "aok" + (tmp++);
        body.append("  br i1 ").append(oob).append(", label %").append(oobBr).append(", label %").append(okBr).append("\n");
        body.append(oobBr).append(":\n");
        throwBuiltin("java/lang/ArrayIndexOutOfBoundsException");
        body.append(okBr).append(":\n");
        terminated = false;
        String ety = "i64";
        String i64 = nv("ai");
        body.append("  ").append(i64).append(" = sext i32 ").append(idx).append(" to i64\n");
        String ep = nv("aep");
        body.append("  ").append(ep).append(" = getelementptr ").append(ety).append(", ptr ").append(ap).append(", i64 ").append(i64).append("\n");
        String base = nv("aep");
body.append("  ").append(base).append(" = getelementptr i8, ptr ").append(ep).append(", i32 ").append(ARRAY_DATA_OFFSET).append("\n");
        return base;
    }

    private void emitNullCheck(String refVal) {
        String isn = nv("nc");
        body.append("  ").append(isn).append(" = icmp eq i64 ").append(refVal).append(", 0\n");
        String nBr = "npe" + (tmp++);
        String okBr = "npok" + (tmp++);
        body.append("  br i1 ").append(isn).append(", label %").append(nBr).append(", label %").append(okBr).append("\n");
        body.append(nBr).append(":\n");
        throwBuiltin("java/lang/NullPointerException");
        body.append(okBr).append(":\n");
        terminated = false;
    }

    private void throwBuiltin(String cls) {
        declares.add("declare ptr @gc_calloc(i64, i64)");
        String raw = nv("te");
        body.append("  ").append(raw).append(" = call ptr @gc_calloc(i64 16, i64 1)\n");
        body.append("  store ptr @.vt_empty, ptr ").append(raw).append("\n");
        String tp = nv("te");
        body.append("  ").append(tp).append(" = getelementptr i8, ptr ").append(raw).append(", i32 8\n");
        body.append("  store i32 ").append(layout.typeId(cls)).append(", ptr ").append(tp).append("\n");
        String iv = nv("te");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(raw).append(" to i64\n");
        body.append("  store i64 ").append(iv).append(", ptr @.pending_exc\n");
        emitUnwind();
    }

    private void newPrimArray(int operand) {
        String kind = switch (operand) {
            case org.objectweb.asm.Opcodes.T_BOOLEAN -> "Z";
            case org.objectweb.asm.Opcodes.T_BYTE -> "B";
            case org.objectweb.asm.Opcodes.T_CHAR -> "C";
            case org.objectweb.asm.Opcodes.T_SHORT -> "S";
            case org.objectweb.asm.Opcodes.T_INT -> "I";
            case org.objectweb.asm.Opcodes.T_FLOAT -> "F";
            case org.objectweb.asm.Opcodes.T_LONG -> "J";
            default -> "D";
        };
        newArray(kind, kind);
    }

    private void newRefArray(String desc) {
        newArray("L", desc);
    }

    private void arrayLoadI8() {
        String ep = arrayElemPtr("B");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i8, ptr ").append(ep).append("\n");
        String e = nv("v");
        body.append("  ").append(e).append(" = sext i8 ").append(v).append(" to i32\n");
        pushInt(e);
    }

    private void arrayLoadChar() {
        String ep = arrayElemPtr("C");
        String w = nv("v");
        body.append("  ").append(w).append(" = load i64, ptr ").append(ep).append("\n");
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(w).append(" to i16\n");
        String e = nv("v");
        body.append("  ").append(e).append(" = zext i16 ").append(t).append(" to i32\n");
        pushInt(e);
    }

    private void arrayLoadShort() {
        String ep = arrayElemPtr("S");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i16, ptr ").append(ep).append("\n");
        String e = nv("v");
        body.append("  ").append(e).append(" = sext i16 ").append(v).append(" to i32\n");
        pushInt(e);
    }

    private void arrayStoreI8() {
        String val = popInt();
        String ep = arrayElemPtr("B");
        String b = nv("v");
        body.append("  ").append(b).append(" = trunc i32 ").append(val).append(" to i8\n");
        body.append("  store i8 ").append(b).append(", ptr ").append(ep).append("\n");
    }

    private void arrayStoreI16() {
        String val = popInt();
        String ep = arrayElemPtr("C");
        String b = nv("v");
        body.append("  ").append(b).append(" = trunc i32 ").append(val).append(" to i16\n");
        body.append("  store i16 ").append(b).append(", ptr ").append(ep).append("\n");
    }

    private void arrayLoadI32() {
        String ep = arrayElemPtr("I");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i32, ptr ").append(ep).append("\n");
        pushInt(v);
    }

    private void arrayLoadI64() {
        String ep = arrayElemPtr("J");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i64, ptr ").append(ep).append("\n");
        pushLong(v);
    }

    private void arrayLoadF32() {
        String ep = arrayElemPtr("F");
        String v = nv("v");
        body.append("  ").append(v).append(" = load float, ptr ").append(ep).append("\n");
        pushFloat(v);
    }

    private void arrayStoreI32() {
        String val = popInt();
        String ep = arrayElemPtr("I");
        body.append("  store i32 ").append(val).append(", ptr ").append(ep).append("\n");
    }

    private void arrayStoreI64() {
        String val = popLong();
        String ep = arrayElemPtr("J");
        body.append("  store i64 ").append(val).append(", ptr ").append(ep).append("\n");
    }

    private void arrayStoreF32() {
        String val = popFloat();
        String ep = arrayElemPtr("F");
        body.append("  store float ").append(val).append(", ptr ").append(ep).append("\n");
    }

    private void getField(FieldInsnNode f) {
        String objv = popRef();
        emitNullCheck(objv);
        String objp = toPtr(objv);
        int off = layout.fieldOffset(f.owner, f.name);
        String fp = nv("fp");
        body.append("  ").append(fp).append(" = getelementptr i8, ptr ").append(objp).append(", i32 ").append(off).append("\n");
        String kind = TypeInfo.kindOf(org.objectweb.asm.Type.getType(f.desc));
        switch (kind) {
            case "I" -> {
                String p32 = nv("p");
                body.append("  ").append(p32).append(" = bitcast ptr ").append(fp).append(" to ptr\n");
                String v = nv("v");
                body.append("  ").append(v).append(" = load i32, ptr ").append(p32).append("\n");
                pushInt(v);
            }
            case "F" -> {
                String v = nv("v");
                body.append("  ").append(v).append(" = load float, ptr ").append(fp).append("\n");
                pushFloat(v);
            }
            default -> {
                String v = nv("v");
                body.append("  ").append(v).append(" = load i64, ptr ").append(fp).append("\n");
                if ("L".equals(kind)) {
                    pushRef(v);
                } else {
                    pushLong(v);
                }
            }
        }
    }

    private void putField(FieldInsnNode f) {
        String kind = TypeInfo.kindOf(org.objectweb.asm.Type.getType(f.desc));
        String val;
        if ("I".equals(kind)) {
            val = popInt();
        } else if ("F".equals(kind)) {
            val = popFloat();
        } else {
            val = popLong();
        }
        String objv = popRef();
        emitNullCheck(objv);
        String objp = toPtr(objv);
        int off = layout.fieldOffset(f.owner, f.name);
        String fp = nv("fp");
        body.append("  ").append(fp).append(" = getelementptr i8, ptr ").append(objp).append(", i32 ").append(off).append("\n");
        if ("I".equals(kind)) {
            body.append("  store i32 ").append(val).append(", ptr ").append(fp).append("\n");
        } else if ("F".equals(kind)) {
            body.append("  store float ").append(val).append(", ptr ").append(fp).append("\n");
        } else {
            body.append("  store i64 ").append(val).append(", ptr ").append(fp).append("\n");
        }
    }

    private void invokeSpecial(MethodInsnNode m) {
        Natives.Sig sig = Natives.lookup(m.owner, m.name, m.desc);
        if (sig != null) {
            invoke(m);
            return;
        }
        List<String> kinds = TypeInfo.argKinds(m.desc);
        String[] raw = new String[kinds.size()];
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = popOfKind(kinds.get(i));
        }
        String objv = popRef();
        if ("java/lang/Thread".equals(m.owner)) {
            if (raw.length == 1) {
                String tp = nv("v");
                body.append("  ").append(tp).append(" = add i64 ").append(raw[0]).append(", 8\n");
                String tpp = nv("v");
                body.append("  ").append(tpp).append(" = inttoptr i64 ").append(tp).append(" to ptr\n");
                String tid = nv("v");
                body.append("  ").append(tid).append(" = load i32, ptr ").append(tpp).append("\n");
                String fn = nv("v");
                body.append("  ").append(fn).append(" = call i64 @aot_tbody_lookup(i32 ").append(tid).append(")\n");
                body.append("  call void @aot_thread_set_body(i64 ").append(objv).append(", i64 ").append(fn)
                    .append(", i64 ").append(raw[0]).append(")\n");
            }
            return;
        }
        if (m.owner.startsWith("java/")) {
            if (isBuiltinContainer(m.owner)) {
                return;
            }
            pushReturnPlaceholder(m.desc);
            return;
        }
        String objp = objv;
        StringBuilder callArgs = new StringBuilder("i64 ").append(objp);
        for (int i = 0; i < kinds.size(); i++) {
            String ak = kinds.get(i);
            callArgs.append(", ").append(slotType(ak)).append(" ").append(raw[i]);
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call ").append(retLlvm(m.desc)).append(" @")
            .append(ClassParser.mangle(m.owner, m.name, m.desc)).append("(").append(callArgs).append(")\n");
        emitCallUnwind();
        pushReturnValue(m.desc, r);
    }

    private void invokeVirtual(MethodInsnNode m) {
        Natives.Sig sig = Natives.lookup(m.owner, m.name, m.desc);
        if (sig != null) {
            invoke(m);
            return;
        }
        if ("java/lang/String".equals(m.owner) && invokeString(m)) {
            return;
        }
        if (invokeStringBuilder(m)) {
            return;
        }
        if (invokeBufferVirtual(m)) {
            return;
        }
        if (invokeCollection(m)) {
            return;
        }
        if (invokeThread(m)) {
            return;
        }
        if (invokeObjectMethod(m)) {
            return;
        }
        List<String> kinds = TypeInfo.argKinds(m.desc);
        String[] raw = new String[kinds.size()];
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = popOfKind(kinds.get(i));
        }
String objv = popRef();
        if (m.owner.startsWith("java/") && !layout.hasSlot(m.name, m.desc)) {
            pushReturnPlaceholder(m.desc);
            return;
        }
        int slot = layout.slotOf(m.name, m.desc);
        String nulv = nv("vn");
        body.append("  ").append(nulv).append(" = icmp eq i64 ").append(objv).append(", 0\n");
        String jl = "Lx" + (labelSeq++);
        body.append("  br i1 ").append(nulv).append(", label %").append(jl).append("_nul, label %").append(jl).append("_go\n");
        body.append(jl).append("_nul:\n");
        String nulZero = "0";
        body.append("  br label %").append(jl).append("_end\n");
        body.append(jl).append("_go:\n");
        String objp2 = nv("vp");
        body.append("  ").append(objp2).append(" = inttoptr i64 ").append(objv).append(" to ptr\n");
        String vtp = nv("vt");
        body.append("  ").append(vtp).append(" = load ptr, ptr ").append(objp2).append("\n");
        String fpp = nv("vf");
        body.append("  ").append(fpp).append(" = getelementptr ptr, ptr ").append(vtp).append(", i32 ").append(slot).append("\n");
        String fnp = nv("fn");
        body.append("  ").append(fnp).append(" = load ptr, ptr ").append(fpp).append("\n");
        StringBuilder callArgs = new StringBuilder("i64 ").append(objv);
        for (int i = 0; i < kinds.size(); i++) {
            String ak = kinds.get(i);
            callArgs.append(", ").append(slotType(ak)).append(" ").append(raw[i]);
        }
        String rc = nv("r");
        String rll = retLlvm(m.desc);
        body.append("  ").append(rc).append(" = call ").append(rll).append(" ").append(fnp).append("(").append(callArgs).append(")\n");
        body.append("  br label %").append(jl).append("_end\n");
        body.append(jl).append("_end:\n");
        String merged = nv("rm");
        body.append("  ").append(merged).append(" = phi ").append(rll).append(" [ ").append(nulZero)
            .append(", %").append(jl).append("_nul ], [ ").append(rc).append(", %").append(jl).append("_go ]\n");
        emitCallUnwind();
        pushReturnValue(m.desc, merged);
    }

    private void invoke(MethodInsnNode m) {
        Natives.Sig sig = Natives.lookup(m.owner, m.name, m.desc);
        if (sig != null) {
            invokeNative(sig, m);
            return;
        }
        if (invokeStaticJdk(m)) {
            return;
        }
        List<String> kinds = TypeInfo.argKinds(m.desc);
        String[] raw = new String[kinds.size()];
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = popOfKind(kinds.get(i));
        }
        if (m.owner.startsWith("java/") || m.owner.startsWith("javax/")) {
            pushReturnPlaceholder(m.desc);
            return;
        }
        StringBuilder callArgs = new StringBuilder();
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) {
                callArgs.append(", ");
            }
            String ak = kinds.get(i);
            callArgs.append(slotType(ak)).append(" ").append(raw[i]);
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call ").append(retLlvm(m.desc)).append(" @")
            .append(ClassParser.mangle(m.owner, m.name, m.desc)).append("(").append(callArgs).append(")\n");
        emitCallUnwind();
        pushReturnValue(m.desc, r);
    }

    private static String slotType(String kind) {
        return switch (kind) {
            case "I", "F" -> "i32";
            default -> "i64";
        };
    }

    private boolean invokeString(MethodInsnNode m) {
        String d = m.desc;
        switch (m.name + d) {
            case "length()I" -> {
                String self = popRef();
                emitStrCall("aot_str_length", "i32", self);
                return true;
            }
            case "charAt(I)C" -> {
                String idx = popInt();
                String self = popRef();
                emitStrCall2("aot_str_char_at", "i32", self, "i32", idx);
                return true;
            }
            case "equals(Ljava/lang/Object;)Z" -> {
                String other = popRef();
                String self = popRef();
                emitStrCall2("aot_str_equals", "i32", self, "i64", other);
                return true;
            }
            case "hashCode()I" -> {
                String self = popRef();
                emitStrCall("aot_str_hash", "i32", self);
                return true;
            }
            case "toString()Ljava/lang/String;" -> {
                pushRef(popRef());
                return true;
            }
            case "isEmpty()Z" -> {
                String self = popRef();
                String lenv = nv("v");
                body.append("  ").append(lenv).append(" = call i32 @aot_str_length(i64 ").append(self).append(")\n");
                String z = nv("v");
                body.append("  ").append(z).append(" = icmp eq i32 ").append(lenv).append(", 0\n");
                String zi = nv("v");
                body.append("  ").append(zi).append(" = zext i1 ").append(z).append(" to i32\n");
                pushInt(zi);
                return true;
            }
            case "substring(I)Ljava/lang/String;" -> {
                String from = popInt();
                String self = popRef();
                String lenv = nv("v");
                body.append("  ").append(lenv).append(" = call i32 @aot_str_length(i64 ").append(self).append(")\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_substring(i64 ").append(self)
                    .append(", i32 ").append(from).append(", i32 ").append(lenv).append(")\n");
                pushRef(r);
                return true;
            }
            case "substring(II)Ljava/lang/String;" -> {
                String to = popInt();
                String from = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_substring(i64 ").append(self)
                    .append(", i32 ").append(from).append(", i32 ").append(to).append(")\n");
                pushRef(r);
                return true;
            }
            case "indexOf(I)I" -> {
                String ch = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_index_of(i64 ").append(self)
                    .append(", i32 ").append(ch).append(", i32 0)\n");
                pushInt(r);
                return true;
            }
            case "indexOf(II)I" -> {
                String from = popInt();
                String ch = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_index_of(i64 ").append(self)
                    .append(", i32 ").append(ch).append(", i32 ").append(from).append("\n");
                pushInt(r);
                return true;
            }
            case "compareTo(Ljava/lang/String;)I" -> {
                String other = popRef();
                String self = popRef();
                emitStrCall2("aot_str_compare_to", "i32", self, "i64", other);
                return true;
            }
            case "concat(Ljava/lang/String;)Ljava/lang/String;" -> {
                String other = popRef();
                String self = popRef();
                emitStrCall2("aot_str_concat2", "i64", self, "i64", other);
                return true;
            }
            case "intern()Ljava/lang/String;" -> {
                pushRef(popRef());
                return true;
            }
            case "toLowerCase()Ljava/lang/String;" -> {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_to_lower(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            case "toUpperCase()Ljava/lang/String;" -> {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_to_upper(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            case "trim()Ljava/lang/String;" -> {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_trim(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            case "startsWith(Ljava/lang/String;)Z" -> {
                String pre = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_starts_with(i64 ").append(self)
                    .append(", i64 ").append(pre).append(")\n");
                pushInt(r);
                return true;
            }
            case "endsWith(Ljava/lang/String;)Z" -> {
                String suf = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_ends_with(i64 ").append(self)
                    .append(", i64 ").append(suf).append(")\n");
                pushInt(r);
                return true;
            }
            case "contains(Ljava/lang/CharSequence;)Z" -> {
                String sub = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_contains(i64 ").append(self)
                    .append(", i64 ").append(sub).append(")\n");
                pushInt(r);
                return true;
            }
            case "indexOf(Ljava/lang/String;)I" -> {
                String sub = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_index_of_str(i64 ").append(self)
                    .append(", i64 ").append(sub).append(", i32 0)\n");
                pushInt(r);
                return true;
            }
            case "indexOf(Ljava/lang/String;I)I" -> {
                String from = popInt();
                String sub = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_index_of_str(i64 ").append(self)
                    .append(", i64 ").append(sub).append(", i32 ").append(from).append(")\n");
                pushInt(r);
                return true;
            }
            case "replace(CC)Ljava/lang/String;" -> {
                String to = popInt();
                String from = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_replace(i64 ").append(self)
                    .append(", i32 ").append(from).append(", i32 ").append(to).append(")\n");
                pushRef(r);
                return true;
            }
            case "lastIndexOf(I)I" -> {
                String ch = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_last_index_of(i64 ").append(self)
                    .append(", i32 ").append(ch).append(")\n");
                pushInt(r);
                return true;
            }
            case "split(Ljava/lang/String;)[Ljava/lang/String;" -> {
                String sep = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_split(i64 ").append(self)
                    .append(", i64 ").append(sep).append(")\n");
                pushRef(r);
                return true;
            }
            case "toCharArray()[C" -> {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_to_char_array(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            case "repeat(I)Ljava/lang/String;" -> {
                String n = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_repeat(i64 ").append(self)
                    .append(", i32 ").append(n).append(")\n");
                pushRef(r);
                return true;
            }
            case "strip()Ljava/lang/String;" -> {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_strip(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            case "regionMatches(ILjava/lang/String;II)Z" -> {
                String len = popInt();
                String ooff = popInt();
                String other = popRef();
                String toff = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_str_region_equals(i64 ").append(self)
                    .append(", i32 ").append(toff).append(", i64 ").append(other)
                    .append(", i32 ").append(ooff).append(", i32 ").append(len).append(")\n");
                pushInt(r);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean isBuiltinContainer(String owner) {
        return "java/lang/StringBuilder".equals(owner) || "java/lang/StringBuffer".equals(owner)
            || "java/util/ArrayList".equals(owner) || "java/util/LinkedList".equals(owner)
            || "java/util/Vector".equals(owner) || "java/util/HashSet".equals(owner)
            || "java/util/LinkedHashSet".equals(owner) || "java/util/HashMap".equals(owner)
            || "java/util/LinkedHashMap".equals(owner) || "java/util/TreeMap".equals(owner)
            || "java/util/Hashtable".equals(owner);
    }

    private void invokeDynamic(InvokeDynamicInsnNode insn) {
        String bsm = insn.bsm.getOwner();
        if ("java/lang/invoke/LambdaMetafactory".equals(bsm)) {
            emitLambda(insn);
            return;
        }
        if (!"java/lang/invoke/StringConcatFactory".equals(bsm)) {
            fail("unsupported invokedynamic bootstrap " + bsm);
            return;
        }
        String recipeText;
        if (insn.bsmArgs.length >= 1 && insn.bsmArgs[0] instanceof String r) {
            recipeText = r;
        } else {
            fail("string concat without recipe");
            return;
        }
        List<String> argKinds = TypeInfo.argKinds(insn.desc);
        String[] slots = new String[argKinds.size()];
        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(insn.desc);
        for (int i = argKinds.size() - 1; i >= 0; i--) {
            slots[i] = popOfKind(argKinds.get(i));
        }
        declares.add("declare i64 @aot_indy_begin()");
        declares.add("declare i64 @aot_indy_end(i64)");
        String sbv = nv("sb");
        body.append("  ").append(sbv).append(" = call i64 @aot_indy_begin()\n");
        int argIdx = 0;
        int i = 0;
        while (i < recipeText.length()) {
            char c = recipeText.charAt(i);
            if (c == '\u0001') {
                if (argIdx < argKinds.size()) {
                    emitIndyArgTyped(sbv, argKinds.get(argIdx), argTypes[argIdx], slots[argIdx]);
                    argIdx++;
                }
                i++;
            } else if (c == '\u0002') {
                if (i + 1 < recipeText.length()) {
                    char lit = recipeText.charAt(i + 1);
                    pushStringLit(String.valueOf(lit));
                    String sv = pop();
                    emitIndyArg(sbv, "L", sv);
                }
                i += 2;
            } else {
                int j = i;
                StringBuilder litBuilder = new StringBuilder();
                while (j < recipeText.length() && recipeText.charAt(j) != '\u0001' && recipeText.charAt(j) != '\u0002') {
                    litBuilder.append(recipeText.charAt(j));
                    j++;
                }
                if (litBuilder.length() > 0) {
                    pushStringLit(litBuilder.toString());
                    String sv = pop();
                    emitIndyArg(sbv, "L", sv);
                }
                i = j;
            }
        }
        String res = nv("v");
        body.append("  ").append(res).append(" = call i64 @aot_indy_end(i64 ").append(sbv).append(")\n");
        pushRef(res);
    }

    private void emitLambda(InvokeDynamicInsnNode insn) {
        String key = LambdaSynthesizer.siteKey(owner.name(), insn.name, insn.desc, insn.bsmArgs);
        String cls = LambdaSynthesizer.lookup(key);
        if (cls == null) {
            fail("lambda site not synthesised: " + insn.name + insn.desc);
            return;
        }
        org.objectweb.asm.Type[] caps = org.objectweb.asm.Type.getArgumentTypes(insn.desc);
        String[] raw = new String[caps.length];
        List<String> kinds = TypeInfo.argKinds(insn.desc);
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = popOfKind(kinds.get(i));
        }
        declares.add("declare ptr @gc_calloc(i64, i64)");
        int size = Math.max(layout.objectSize(cls), Layout.HEADER_SIZE + 16);
        String rawPtr = nv("lp");
        body.append("  ").append(rawPtr).append(" = call ptr @gc_calloc(i64 ").append(size).append(", i64 1)\n");
        String addr = nv("la");
        body.append("  ").append(addr).append(" = ptrtoint ptr ").append(rawPtr).append(" to i64\n");
        String vtLabel = layout.vtableLabel(cls);
        String vtoff = nv("lv");
        body.append("  ").append(vtoff).append(" = getelementptr i8, ptr ").append(rawPtr).append(", i32 0\n");
        String vtpp = nv("lvp");
        body.append("  ").append(vtpp).append(" = bitcast ptr ").append(vtoff).append(" to ptr\n");
        body.append("  store ptr ").append(vtLabel).append(", ptr ").append(vtpp).append("\n");
        String tip = nv("lt");
        body.append("  ").append(tip).append(" = getelementptr i8, ptr ").append(rawPtr).append(", i32 8\n");
        String tpp = nv("ltp");
        body.append("  ").append(tpp).append(" = bitcast ptr ").append(tip).append(" to ptr\n");
        body.append("  store i32 ").append(layout.typeId(cls)).append(", ptr ").append(tpp).append("\n");
        for (int i = 0; i < caps.length; i++) {
            String fp = nv("lf");
            body.append("  ").append(fp).append(" = getelementptr i8, ptr ").append(rawPtr)
                .append(", i32 ").append(layout.fieldOffset(cls, "c" + i)).append("\n");
            String fpp = nv("lfp");
            body.append("  ").append(fpp).append(" = bitcast ptr ").append(fp).append(" to ptr\n");
            body.append("  store i64 ").append(raw[i]).append(", ptr ").append(fpp).append("\n");
        }
        pushRef(addr);
    }

    private void emitIndyArgTyped(String sbv, String kind, org.objectweb.asm.Type type, String value) {
        if (type != null && type.getSort() == org.objectweb.asm.Type.CHAR) {
            declares.add("declare i64 @aot_indy_char(i64, i32)");
            body.append("  call i64 @aot_indy_char(i64 ").append(sbv).append(", i32 ").append(value).append(")\n");
            return;
        }
        if (type != null && type.getSort() == org.objectweb.asm.Type.BOOLEAN) {
            declares.add("declare i64 @aot_indy_bool(i64, i32)");
            body.append("  call i64 @aot_indy_bool(i64 ").append(sbv).append(", i32 ").append(value).append(")\n");
            return;
        }
        emitIndyArg(sbv, kind, value);
    }

    private void emitIndyArg(String sbv, String kind, String value) {
        switch (kind) {
            case "I" -> {
                declares.add("declare i64 @aot_indy_int(i64, i32)");
                body.append("  call i64 @aot_indy_int(i64 ").append(sbv).append(", i32 ").append(value).append(")\n");
            }
            case "J" -> {
                declares.add("declare i64 @aot_indy_long(i64, i64)");
                body.append("  call i64 @aot_indy_long(i64 ").append(sbv).append(", i64 ").append(value).append(")\n");
            }
            case "F" -> {
                declares.add("declare i64 @aot_indy_float(i64, i32)");
                body.append("  call i64 @aot_indy_float(i64 ").append(sbv).append(", i32 ").append(value).append(")\n");
            }
            case "D" -> {
                declares.add("declare i64 @aot_indy_double(i64, i64)");
                body.append("  call i64 @aot_indy_double(i64 ").append(sbv).append(", i64 ").append(value).append(")\n");
            }
            default -> {
                declares.add("declare i64 @aot_indy_obj(i64, i64)");
                body.append("  call i64 @aot_indy_obj(i64 ").append(sbv).append(", i64 ").append(value).append(")\n");
            }
        }
    }

    private boolean invokeThread(MethodInsnNode m) {
        String owner = m.owner;
        String key = m.name + m.desc;
        if (!"java/lang/Thread".equals(owner) && !"java/lang/Runnable".equals(owner)) {
            return false;
        }
        if ("start()V".equals(key)) {
            String self = popRef();
            body.append("  call void @aot_thread_start(i64 ").append(self).append(")\n");
            return true;
        }
        if ("join()V".equals(key)) {
            String self = popRef();
            body.append("  call void @aot_thread_join(i64 ").append(self).append(")\n");
            return true;
        }
        if ("run()V".equals(key)) {
            return false;
        }
        return false;
    }

    private boolean invokeStaticThread(MethodInsnNode m) {
        if (!"java/lang/Thread".equals(m.owner)) {
            return false;
        }
        String key = m.name + m.desc;
        if ("sleep(J)V".equals(key)) {
            String v = popLong();
            body.append("  call void @aot_thread_sleep(i64 ").append(v).append(")\n");
            return true;
        }
        if ("yield()V".equals(key)) {
            body.append("  call void @aot_thread_yield()\n");
            return true;
        }
        if ("currentThread()Ljava/lang/Thread;".equals(key)) {
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_thread_rec_new()\n");
            pushRef(r);
            return true;
        }
        return false;
    }

    private static String bufTidGetter(String owner) {
        String base = owner;
        if (base.startsWith("bufs/")) {
            base = "java/nio/" + base.substring(5);
        }
        return switch (base) {
            case "java/nio/ByteBuffer" -> "byte";
            case "java/nio/ShortBuffer" -> "short";
            case "java/nio/CharBuffer" -> "char";
            case "java/nio/IntBuffer" -> "int";
            case "java/nio/LongBuffer" -> "long";
            case "java/nio/FloatBuffer" -> "float";
            case "java/nio/DoubleBuffer" -> "double";
            case "org/lwjgl/system/PointerBuffer" -> "ptr";
            default -> null;
        };
    }

    private static String canonicalBuf(String owner) {
        if (owner.startsWith("bufs/")) {
            return "java/nio/" + owner.substring(5);
        }
        return owner;
    }

    private boolean invokeBufferVirtual(MethodInsnNode m) {
        String kind = bufTidGetter(m.owner);
        if (kind == null) {
            return false;
        }
        String key = m.name + m.desc;
        boolean isFlip = "flip".equals(m.name);
        boolean isRewind = "rewind".equals(m.name);
        boolean isClear = "clear".equals(m.name);
        if (isFlip) {
            String self = popRef();
            declares.add("declare i64 @aot_buf_flip(i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_flip(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        if (isRewind) {
            String self = popRef();
            declares.add("declare i64 @aot_buf_rewind(i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_rewind(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        if (isClear) {
            String self = popRef();
            declares.add("declare i64 @aot_buf_clear(i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_clear(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        switch (key) {
            case "capacity()I" -> {
                String self = popRef();
                declares.add("declare i32 @aot_buf_capacity(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_buf_capacity(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            case "position()I" -> {
                String self = popRef();
                declares.add("declare i32 @aot_buf_position(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_buf_position(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            case "limit()I" -> {
                String self = popRef();
                declares.add("declare i32 @aot_buf_limit(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_buf_limit(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            case "remaining()I" -> {
                String self = popRef();
                declares.add("declare i32 @aot_buf_remaining(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_buf_remaining(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            case "hasRemaining()Z" -> {
                String self = popRef();
                declares.add("declare i32 @aot_buf_has_remaining(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_buf_has_remaining(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            case "position(I)Lbufs/ByteBuffer;", "position(I)Ljava/nio/Buffer;" -> {
                String p = popInt();
                String self = popRef();
                declares.add("declare i64 @aot_buf_set_position(i64, i32)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_buf_set_position(i64 ").append(self)
                    .append(", i32 ").append(p).append(")\n");
                pushRef(r);
                return true;
            }
            case "limit(I)Lbufs/ByteBuffer;", "limit(I)Ljava/nio/Buffer;" -> {
                String l = popInt();
                String self = popRef();
                declares.add("declare i64 @aot_buf_set_limit(i64, i32)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_buf_set_limit(i64 ").append(self)
                    .append(", i32 ").append(l).append(")\n");
                pushRef(r);
                return true;
            }
            default -> {
                return invokeBufferTyped(m, kind, key);
            }
        }
    }

    private boolean invokeBufferTyped(MethodInsnNode m, String kind, String key) {
        String[] parts = splitAccessor(m.name, m.desc);
        if (parts == null) {
            return false;
        }
        boolean isPut = "put".equals(parts[0]);
        boolean hasIndex = "1".equals(parts[1]);
        String kindCode = parts[2];
        int width = switch (kindCode) {
            case "B" -> 1;
            case "S", "C" -> 2;
            case "I", "F" -> 4;
            default -> 8;
        };
        declares.add("declare i64 @aot_buf_read(i64, i32, i32)");
        declares.add("declare void @aot_buf_write(i64, i32, i32, i64)");
        declares.add("declare i32 @aot_buf_position(i64)");
        declares.add("declare i64 @aot_buf_set_position(i64, i32)");
        if (isPut) {
            String val = valueAsBits(kindCode);
            String self;
            String off;
            if (hasIndex) {
                String idx = popInt();
                self = popRef();
                String scaled = nv("v");
                body.append("  ").append(scaled).append(" = mul i32 ").append(idx).append(", ").append(width).append("\n");
                off = scaled;
                body.append("  call void @aot_buf_write(i64 ").append(self).append(", i32 ").append(off)
                    .append(", i32 ").append(width).append(", i64 ").append(val).append(")\n");
                pushRef(self);
                return true;
            }
            self = popRef();
            String pos = nv("v");
            body.append("  ").append(pos).append(" = call i32 @aot_buf_position(i64 ").append(self).append(")\n");
            String scaled = nv("v");
            body.append("  ").append(scaled).append(" = mul i32 ").append(pos).append(", ").append(width).append("\n");
            body.append("  call void @aot_buf_write(i64 ").append(self).append(", i32 ").append(scaled)
                .append(", i32 ").append(width).append(", i64 ").append(val).append(")\n");
            String np = nv("v");
            body.append("  ").append(np).append(" = add i32 ").append(pos).append(", 1\n");
            body.append("  call i64 @aot_buf_set_position(i64 ").append(self).append(", i32 ").append(np).append(")\n");
            pushRef(self);
            return true;
        }
        String self;
        String off;
        if (hasIndex) {
            String idx = popInt();
            self = popRef();
            String scaled = nv("v");
            body.append("  ").append(scaled).append(" = mul i32 ").append(idx).append(", ").append(width).append("\n");
            off = scaled;
        } else {
            self = popRef();
            String pos = nv("v");
            body.append("  ").append(pos).append(" = call i32 @aot_buf_position(i64 ").append(self).append(")\n");
            String scaled = nv("v");
            body.append("  ").append(scaled).append(" = mul i32 ").append(pos).append(", ").append(width).append("\n");
            off = scaled;
            String np = nv("v");
            body.append("  ").append(np).append(" = add i32 ").append(pos).append(", 1\n");
            body.append("  call i64 @aot_buf_set_position(i64 ").append(self).append(", i32 ").append(np).append(")\n");
        }
        String raw = nv("v");
        body.append("  ").append(raw).append(" = call i64 @aot_buf_read(i64 ").append(self)
            .append(", i32 ").append(off).append(", i32 ").append(width).append(")\n");
        pushFromBits(kindCode, raw);
        return true;
    }

    private String valueAsBits(String kindCode) {
        switch (kindCode) {
            case "F" -> {
                String v = popFloat();
                String b = nv("v");
                body.append("  ").append(b).append(" = bitcast float ").append(v).append(" to i32\n");
                String e = nv("v");
                body.append("  ").append(e).append(" = sext i32 ").append(b).append(" to i64\n");
                return e;
            }
            case "D" -> {
                String v = popDouble();
                String b = nv("v");
                body.append("  ").append(b).append(" = bitcast double ").append(v).append(" to i64\n");
                return b;
            }
            case "I", "S", "C", "B" -> {
                String v = popInt();
                String e = nv("v");
                body.append("  ").append(e).append(" = sext i32 ").append(v).append(" to i64\n");
                return e;
            }
            default -> {
                return popLong();
            }
        }
    }

    private void pushFromBits(String kindCode, String raw) {
        switch (kindCode) {
            case "F" -> {
                String t = nv("v");
                body.append("  ").append(t).append(" = trunc i64 ").append(raw).append(" to i32\n");
                String g = nv("v");
                body.append("  ").append(g).append(" = bitcast i32 ").append(t).append(" to float\n");
                pushFloat(g);
            }
            case "D" -> {
                String d = nv("v");
                body.append("  ").append(d).append(" = bitcast i64 ").append(raw).append(" to double\n");
                pushDouble(d);
            }
            case "I", "S", "C", "B" -> {
                String t = nv("v");
                body.append("  ").append(t).append(" = trunc i64 ").append(raw).append(" to i32\n");
                pushInt(t);
            }
            default -> pushLong(raw);
        }
    }

    private static String[] splitAccessor(String name, String desc) {
        String rc;
        if ("get".equals(name) || "put".equals(name)) {
            String ret = org.objectweb.asm.Type.getReturnType(desc).getDescriptor();
            String arg = org.objectweb.asm.Type.getArgumentTypes(desc).length > 0
                ? org.objectweb.asm.Type.getArgumentTypes(desc)[org.objectweb.asm.Type.getArgumentTypes(desc).length - 1].getDescriptor()
                : "V";
            String probe = "get".equals(name) ? ret : arg;
            rc = switch (probe) {
                case "B" -> "B";
                case "S" -> "S";
                case "C" -> "C";
                case "I" -> "I";
                case "J" -> "J";
                case "F" -> "F";
                case "D" -> "D";
                default -> null;
            };
            if (rc == null) {
                return null;
            }
            boolean hasIdx = org.objectweb.asm.Type.getArgumentTypes(desc).length > ("get".equals(name) ? 0 : 1);
            return new String[] { "get".equals(name) ? "get" : "put", hasIdx ? "1" : "0", rc };
        }
        rc = switch (name) {
            case "getInt", "putInt" -> "I";
            case "getFloat", "putFloat" -> "F";
            case "getDouble", "putDouble" -> "D";
            case "getLong", "putLong" -> "J";
            case "getShort", "putShort" -> "S";
            case "getChar", "putChar" -> "C";
            case "getByte", "putByte" -> "B";
            default -> null;
        };
        if (rc == null) {
            return null;
        }
        boolean put = name.startsWith("put");
        boolean hasIndex;
        if (put) {
            hasIndex = org.objectweb.asm.Type.getArgumentTypes(desc).length > 1;
        } else {
            hasIndex = org.objectweb.asm.Type.getArgumentTypes(desc).length > 0;
        }
        return new String[] { put ? "put" : "get", hasIndex ? "1" : "0", rc };
    }

    private static int bufKindCode(String kind) {
        return switch (kind) {
            case "byte" -> 0;
            case "short" -> 1;
            case "char" -> 2;
            case "int" -> 3;
            case "long" -> 4;
            case "float" -> 5;
            case "double" -> 6;
            default -> 7;
        };
    }

    private boolean invokeBufferStatic(MethodInsnNode m) {
        String kind = bufTidGetter(m.owner);
        if (kind == null) {
            return false;
        }
        String key = m.name + m.desc;
        declares.add("declare i64 @aot_buf_alloc(i32, i32)");
        declares.add("declare i64 @aot_buf_wrap(i32, i64, i32)");
        declares.add("declare i64 @aot_buf_addr(i64)");
        String tid = String.valueOf(bufKindCode(kind));
        if ("allocateDirect(I)".equals(m.name + m.desc.substring(0, m.desc.indexOf(')') + 1))
            || "allocate(I)".equals(m.name + m.desc.substring(0, m.desc.indexOf(')') + 1))) {
            String cap = popInt();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_alloc(i32 ").append(tid)
                .append(", i32 ").append(cap).append(")\n");
            pushRef(r);
            return true;
        }
        if ("create".equals(m.name) && m.desc.startsWith("(JI)")) {
            String cap = popInt();
            String addr = popLong();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_wrap(i32 ").append(tid)
                .append(", i64 ").append(addr).append(", i32 ").append(cap).append(")\n");
            pushRef(r);
            return true;
        }
        if ("create".equals(m.name) && m.desc.startsWith("(I)")) {
            String cap = popInt();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_buf_alloc(i32 ").append(tid)
                .append(", i32 ").append(cap).append(")\n");
            pushRef(r);
            return true;
        }
        return false;
    }

    private boolean invokeCollection(MethodInsnNode m) {
        String owner = m.owner;
        String key = m.name + m.desc;
        boolean isList = "java/util/ArrayList".equals(owner) || "java/util/LinkedList".equals(owner)
            || "java/util/Vector".equals(owner) || "java/util/List".equals(owner)
            || "java/util/Collection".equals(owner) || "java/util/HashSet".equals(owner)
            || "java/util/LinkedHashSet".equals(owner) || "java/util/Set".equals(owner)
            || "java/lang/Iterable".equals(owner);
        boolean isMap = "java/util/HashMap".equals(owner) || "java/util/LinkedHashMap".equals(owner)
            || "java/util/TreeMap".equals(owner) || "java/util/Hashtable".equals(owner)
            || "java/util/Map".equals(owner);
        if (isList) {
            if ("add(Ljava/lang/Object;)Z".equals(key)) {
                String v = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_list_add(i64 ").append(self)
                    .append(", i64 ").append(v).append(")\n");
                pushIntLit(1);
                return true;
            }
            if ("get(I)Ljava/lang/Object;".equals(key)) {
                String idx = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_list_get(i64 ").append(self)
                    .append(", i32 ").append(idx).append(")\n");
                pushRef(r);
                return true;
            }
            if ("size()I".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_list_size(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            if ("set(ILjava/lang/Object;)Ljava/lang/Object;".equals(key)) {
                String v = popRef();
                String idx = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_list_set(i64 ").append(self)
                    .append(", i32 ").append(idx).append(", i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("remove(I)Ljava/lang/Object;".equals(key)) {
                String idx = popInt();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_list_remove(i64 ").append(self)
                    .append(", i32 ").append(idx).append(")\n");
                pushRef(r);
                return true;
            }
            if ("isEmpty()Z".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_list_is_empty(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            if ("clear()V".equals(key)) {
                String self = popRef();
                body.append("  call void @aot_list_clear(i64 ").append(self).append(")\n");
                return true;
            }
            if ("contains(Ljava/lang/Object;)Z".equals(key)) {
                String v = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_list_contains(i64 ").append(self)
                    .append(", i64 ").append(v).append(")\n");
                pushInt(r);
                return true;
            }
            if ("indexOf(Ljava/lang/Object;)I".equals(key)) {
                String v = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_list_index_of(i64 ").append(self)
                    .append(", i64 ").append(v).append(")\n");
                pushInt(r);
                return true;
            }
            if ("toArray()[Ljava/lang/Object;".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_list_to_array(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        if (isMap) {
            if ("put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
                String v = popRef();
                String k = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_map_put(i64 ").append(self)
                    .append(", i64 ").append(k).append(", i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("get(Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
                String k = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_map_get(i64 ").append(self)
                    .append(", i64 ").append(k).append(")\n");
                pushRef(r);
                return true;
            }
            if ("remove(Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
                String k = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_map_remove(i64 ").append(self)
                    .append(", i64 ").append(k).append(")\n");
                pushRef(r);
                return true;
            }
            if ("size()I".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_map_size(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            if ("isEmpty()Z".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_map_size(i64 ").append(self).append(")\n");
                String z = nv("v");
                body.append("  ").append(z).append(" = icmp eq i32 ").append(r).append(", 0\n");
                String zi = nv("v");
                body.append("  ").append(zi).append(" = zext i1 ").append(z).append(" to i32\n");
                pushInt(zi);
                return true;
            }
            if ("containsKey(Ljava/lang/Object;)Z".equals(key)) {
                String k = popRef();
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_map_contains(i64 ").append(self)
                    .append(", i64 ").append(k).append(")\n");
                pushInt(r);
                return true;
            }
            if ("containsValue(Ljava/lang/Object;)Z".equals(key)) {
                String v = popRef();
                String self = popRef();
                String kl = nv("v");
                body.append("  ").append(kl).append(" = call i64 @aot_map_keys(i64 ").append(self).append(")\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_list_contains(i64 ").append(kl)
                    .append(", i64 ").append(v).append(")\n");
                pushInt(r);
                return true;
            }
            if ("keySet()Ljava/util/Set;".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_map_keys(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            if ("values()Ljava/util/Collection;".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_map_values(i64 ").append(self).append(")\n");
                pushRef(r);
                return true;
            }
            if ("clear()V".equals(key)) {
                String self = popRef();
                body.append("  call void @aot_map_clear(i64 ").append(self).append(")\n");
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean invokeFfi(String owner, MethodInsnNode m) {
        FfiSupport.Bind b = FfiSupport.lookup(owner, m.name, m.desc);
        if (b == null) {
            return false;
        }
        int want = b.args.length;
        String[] raw = new String[want];
        for (int i = want - 1; i >= 0; i--) {
            raw[i] = popByKind(b.args[i]);
        }
        String[] argTypes = new String[want];
        StringBuilder sig = new StringBuilder();
        StringBuilder callArgs = new StringBuilder();
        for (int i = 0; i < want; i++) {
            argTypes[i] = llvmOfKind(b.args[i]);
            if (i > 0) {
                sig.append(", ");
                callArgs.append(", ");
            }
            sig.append(argTypes[i]);
            callArgs.append(argTypes[i]).append(" ").append(raw[i]);
        }
        String retType = llvmOfKind(b.ret);
        declares.add("declare " + retType + " @" + b.cName + "(" + sig + ")");
        if (b.ret == FfiSupport.K_VOID) {
            body.append("  call void @").append(b.cName).append("(").append(callArgs).append(")\n");
            return true;
        }
        String r = nv("v");
        body.append("  ").append(r).append(" = call ").append(retType)
            .append(" @").append(b.cName).append("(").append(callArgs).append(")\n");
        pushByKind(b.ret, r);
        return true;
    }

    private String popByKind(int kind) {
        return switch (kind) {
            case FfiSupport.K_I32 -> popInt();
            case FfiSupport.K_F32 -> popFloat();
            case FfiSupport.K_F64 -> popDouble();
            case FfiSupport.K_I64 -> popLong();
            default -> popRef();
        };
    }

    private void pushByKind(int kind, String v) {
        switch (kind) {
            case FfiSupport.K_I32 -> pushInt(v);
            case FfiSupport.K_F32 -> pushFloat(v);
            case FfiSupport.K_F64 -> pushDouble(v);
            case FfiSupport.K_I64 -> pushLong(v);
            default -> pushRef(v);
        }
    }

    private static String llvmOfKind(int kind) {
        return switch (kind) {
            case FfiSupport.K_VOID -> "void";
            case FfiSupport.K_I32 -> "i32";
            case FfiSupport.K_F32 -> "float";
            case FfiSupport.K_F64 -> "double";
            default -> "i64";
        };
    }

    private boolean invokeStaticJdk(MethodInsnNode m) {
        String owner = m.owner;
        String key = m.name + m.desc;
        if (invokeFfi(owner, m)) {
            return true;
        }
        if (invokeBufferStatic(m)) {
            return true;
        }
        if (invokeStaticThread(m)) {
            return true;
        }
        if ("java/lang/System".equals(owner)) {
            if ("nanoTime()J".equals(key)) {
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_system_nano()\n");
                pushLong(r);
                return true;
            }
            if ("currentTimeMillis()J".equals(key)) {
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_system_millis()\n");
                pushLong(r);
                return true;
            }
            if ("arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V".equals(key)) {
                String len = popInt();
                String dpos = popInt();
                String dst = popRef();
                String spos = popInt();
                String src = popRef();
                int width = 8;
                org.objectweb.asm.Type[] at = org.objectweb.asm.Type.getArgumentTypes(m.desc);
                if (at.length == 5) {
                    org.objectweb.asm.Type t0 = at[0];
                    if (t0.getSort() == org.objectweb.asm.Type.ARRAY) {
                        String ed = t0.getElementType().getDescriptor();
                        width = switch (ed) {
                            case "B", "Z" -> 1;
                            case "C", "S" -> 2;
                            case "I", "F" -> 4;
                            case "J", "D" -> 8;
                            default -> 8;
                        };
                    }
                }
                body.append("  call void @aot_system_arraycopy(i64 ").append(src).append(", i32 ").append(spos)
                    .append(", i64 ").append(dst).append(", i32 ").append(dpos).append(", i32 ").append(len)
                    .append(", i32 ").append(width).append(")\n");
                return true;
            }
            if ("gc()V".equals(key)) {
                body.append("  call void @aot_system_gc()\n");
                return true;
            }
            return false;
        }
        if ("java/lang/Class".equals(owner)) {
            if ("forName(Ljava/lang/String;)Ljava/lang/Class;".equals(key)) {
                String nm = popRef();
                declares.add("declare i64 @aot_class_for_name(i64)");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_class_for_name(i64 ").append(nm).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        if ("java/util/Arrays".equals(owner)) {
            if ("sort([I)V".equals(key)) {
                String arr = popRef();
                body.append("  call i64 @aot_arrays_sort_int(i64 ").append(arr).append(")\n");
                return true;
            }
            if ("sort([J)V".equals(key) || "sort([Ljava/lang/Object;)V".equals(key)) {
                String arr = popRef();
                body.append("  call i64 @aot_arrays_sort(i64 ").append(arr).append(")\n");
                return true;
            }
            if ("equals([I[I)Z".equals(key)) {
                String b = popRef();
                String a = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_arrays_equals_int(i64 ").append(a)
                    .append(", i64 ").append(b).append(")\n");
                pushInt(r);
                return true;
            }
            if ("equals([J[J)Z".equals(key)
                    || "equals([Ljava/lang/Object;[Ljava/lang/Object;)Z".equals(key)) {
                String b = popRef();
                String a = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_arrays_equals(i64 ").append(a)
                    .append(", i64 ").append(b).append(")\n");
                pushInt(r);
                return true;
            }
            if ("copyOf([II)[I".equals(key) || "copyOf([JI)[J".equals(key)
                    || "copyOf([Ljava/lang/Object;I)[Ljava/lang/Object;".equals(key)) {
                String n = popInt();
                String arr = popRef();
                int width = "copyOf([II)[I".equals(key) ? 4 : 8;
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_arrays_copy_of_width(i64 ").append(arr)
                    .append(", i32 ").append(n).append(", i32 ").append(width).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        if ("java/lang/String".equals(owner)) {
            if ("format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;".equals(key)) {
                String arr = popRef();
                String fmt = popRef();
                declares.add("declare i64 @aot_object_array_ptr(i64)");
                declares.add("declare i64 @aot_str_format(i64, i64)");
                String ap = nv("v");
                body.append("  ").append(ap).append(" = call i64 @aot_object_array_ptr(i64 ").append(arr).append(")\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_format(i64 ").append(fmt)
                    .append(", i64 ").append(ap).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(I)Ljava/lang/String;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_int(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(J)Ljava/lang/String;".equals(key)) {
                String v = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_long(i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(D)Ljava/lang/String;".equals(key)) {
                String v = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_double(double ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(F)Ljava/lang/String;".equals(key)) {
                String v = popFloat();
                String d = nv("v");
                body.append("  ").append(d).append(" = fpext float ").append(v).append(" to double\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_double(double ").append(d).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(Z)Ljava/lang/String;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_bool(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(C)Ljava/lang/String;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_char(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("valueOf(Ljava/lang/Object;)Ljava/lang/String;".equals(key)) {
                String v = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_object(i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Character".equals(owner)) {
            if ("valueOf(C)Ljava/lang/Character;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_box_char(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("charValue()C".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_box_int(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Boolean".equals(owner)) {
            if ("valueOf(Z)Ljava/lang/Boolean;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_box_bool(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("booleanValue()Z".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_box_int(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Double".equals(owner)) {
            if ("doubleToLongBits(D)J".equals(key) || "doubleToRawLongBits(D)J".equals(key)) {
                String v = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast double ").append(v).append(" to i64\n");
                pushLong(r);
                return true;
            }
            if ("longBitsToDouble(J)D".equals(key)) {
                String v = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast i64 ").append(v).append(" to double\n");
                pushDouble(r);
                return true;
            }
            if ("floatToIntBits(F)I".equals(key) || "floatToRawIntBits(F)I".equals(key)) {
                String v = popFloat();
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast float ").append(v).append(" to i32\n");
                pushInt(r);
                return true;
            }
            if ("intBitsToFloat(I)F".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast i32 ").append(v).append(" to float\n");
                pushFloat(r);
                return true;
            }
            if ("isNaN(D)Z".equals(key)) {
                String v = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = fcmp uno double ").append(v).append(", ").append(v).append("\n");
                String z = nv("v");
                body.append("  ").append(z).append(" = zext i1 ").append(r).append(" to i32\n");
                pushInt(z);
                return true;
            }
            if ("valueOf(D)Ljava/lang/Double;".equals(key)) {
                String v = popDouble();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast double ").append(v).append(" to i64\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_box_double(i64 ").append(bits).append(")\n");
                pushRef(r);
                return true;
            }
            if ("doubleValue()D".equals(key)) {
                String self = popRef();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = call i64 @aot_unbox_double(i64 ").append(self).append(")\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast i64 ").append(bits).append(" to double\n");
                pushDouble(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Float".equals(owner)) {
            if ("valueOf(F)Ljava/lang/Float;".equals(key)) {
                String v = popFloat();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast float ").append(v).append(" to i32\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_box_float(i32 ").append(bits).append(")\n");
                pushRef(r);
                return true;
            }
            if ("floatValue()F".equals(key)) {
                String self = popRef();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = call i32 @aot_unbox_float(i64 ").append(self).append(")\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = bitcast i32 ").append(bits).append(" to float\n");
                pushFloat(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Integer".equals(owner)) {
            if ("parseInt(Ljava/lang/String;)I".equals(key)) {
                String v = popRef();
                emitParseGuard(v);
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_int_parse(i64 ").append(v).append(")\n");
                pushInt(r);
                return true;
            }
            if ("valueOf(I)Ljava/lang/Integer;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_box_new(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("intValue()I".equals(key)) {
                String self = popRef();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i32 @aot_box_int(i64 ").append(self).append(")\n");
                pushInt(r);
                return true;
            }
            if ("toString(I)Ljava/lang/String;".equals(key)) {
                String v = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_int(i32 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        if ("java/lang/Long".equals(owner)) {
            if ("parseLong(Ljava/lang/String;)J".equals(key)) {
                String v = popRef();
                emitParseGuard(v);
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_long_parse(i64 ").append(v).append(")\n");
                pushLong(r);
                return true;
            }
            if ("valueOf(J)Ljava/lang/Long;".equals(key)) {
                String v = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_long_box_new(i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            if ("toString(J)Ljava/lang/String;".equals(key)) {
                String v = popLong();
                String r = nv("v");
                body.append("  ").append(r).append(" = call i64 @aot_str_of_long(i64 ").append(v).append(")\n");
                pushRef(r);
                return true;
            }
            return false;
        }
        return false;
    }

    private void emitClassLiteral(org.objectweb.asm.Type t) {
        String desc;
        switch (t.getSort()) {
            case org.objectweb.asm.Type.OBJECT -> desc = t.getInternalName();
            case org.objectweb.asm.Type.ARRAY -> desc = t.getDescriptor();
            case org.objectweb.asm.Type.BOOLEAN -> desc = "Z";
            case org.objectweb.asm.Type.BYTE -> desc = "B";
            case org.objectweb.asm.Type.CHAR -> desc = "C";
            case org.objectweb.asm.Type.SHORT -> desc = "S";
            case org.objectweb.asm.Type.INT -> desc = "I";
            case org.objectweb.asm.Type.LONG -> desc = "J";
            case org.objectweb.asm.Type.FLOAT -> desc = "F";
            case org.objectweb.asm.Type.DOUBLE -> desc = "D";
            case org.objectweb.asm.Type.VOID -> desc = "V";
            default -> {
                pushIntLit(0);
                return;
            }
        }
        declares.add("declare i64 @aot_class_of_type(i32)");
        String r = nv("v");
        body.append("  ").append(r).append(" = call i64 @aot_class_of_type(i32 ")
            .append(layout.typeId(desc)).append(")\n");
        pushRef(r);
    }

    private void emitIllegalMonitor(String rc) {
        String bad = nv("im");
        body.append("  ").append(bad).append(" = icmp slt i32 ").append(rc).append(", 0\n");
        String badBr = "imse" + (tmp++);
        String okBr = "imsok" + (tmp++);
        body.append("  br i1 ").append(bad).append(", label %").append(badBr).append(", label %").append(okBr).append("\n");
        body.append(badBr).append(":\n");
        throwBuiltin("java/lang/IllegalMonitorStateException");
        body.append(okBr).append(":\n");
        terminated = false;
    }

    private void emitParseGuard(String sv) {
        declares.add("declare i32 @aot_int_parse_ok(i64)");
        String ok = nv("pg");
        body.append("  ").append(ok).append(" = call i32 @aot_int_parse_ok(i64 ").append(sv).append(")\n");
        String bad = nv("pg");
        body.append("  ").append(bad).append(" = icmp eq i32 ").append(ok).append(", 0\n");
        String badBr = "nfe" + (tmp++);
        String okBr = "nfok" + (tmp++);
        body.append("  br i1 ").append(bad).append(", label %").append(badBr).append(", label %").append(okBr).append("\n");
        body.append(badBr).append(":\n");
        throwBuiltin("java/lang/NumberFormatException");
        body.append(okBr).append(":\n");
        terminated = false;
    }

    private boolean invokeStringBuilder(MethodInsnNode m) {
        String owner = m.owner;
        if (!"java/lang/StringBuilder".equals(owner) && !"java/lang/StringBuffer".equals(owner)) {
            return false;
        }
        String key = m.name + m.desc;
        if ("length()I".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_sb_length(i64 ").append(self).append(")\n");
            pushInt(r);
            return true;
        }
        if ("toString()Ljava/lang/String;".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_sb_to_string(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        if ("reverse()Ljava/lang/StringBuilder;".equals(key) || "reverse()Ljava/lang/StringBuffer;".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_sb_reverse(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        if (m.name.equals("append")) {
            return emitSbAppend(m);
        }
        return false;
    }

    private boolean emitSbAppend(MethodInsnNode m) {
        List<String> kinds = TypeInfo.argKinds(m.desc);
        if (kinds.size() != 1) {
            return false;
        }
        String k = kinds.get(0);
        String aDesc = org.objectweb.asm.Type.getArgumentTypes(m.desc)[0].getDescriptor();
        if ("C".equals(aDesc)) {
            String cv = popInt();
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_sb_append_char(i64 ").append(self)
                .append(", i32 ").append(cv).append(")\n");
            pushRef(r);
            return true;
        }
        if ("Z".equals(aDesc)) {
            String zv = popInt();
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_sb_append_bool(i64 ").append(self)
                .append(", i32 ").append(zv).append(")\n");
            pushRef(r);
            return true;
        }
        String arg;
        String fn;
        String at;
        switch (k) {
            case "I" -> {
                arg = popInt();
                fn = "aot_sb_append_int";
                at = "i32";
            }
            case "J" -> {
                arg = popLong();
                fn = "aot_sb_append_long";
                at = "i64";
            }
            case "F" -> {
                arg = popFloat();
                fn = "aot_sb_append_float";
                at = "float";
            }
            case "D" -> {
                arg = popDouble();
                fn = "aot_sb_append_double";
                at = "double";
            }
            case "L" -> {
                String inner = org.objectweb.asm.Type.getArgumentTypes(m.desc)[0].getClassName();
                if (!"java.lang.String".equals(inner) && !"java.lang.CharSequence".equals(inner)
                        && !"java.lang.Object".equals(inner)) {
                    return false;
                }
                arg = popRef();
                fn = "aot_sb_append_str";
                at = "i64";
            }
            default -> {
                return false;
            }
        }
        String self = popRef();
        String r = nv("v");
        body.append("  ").append(r).append(" = call i64 @").append(fn).append("(i64 ").append(self)
            .append(", ").append(at).append(" ").append(arg).append(")\n");
        pushRef(r);
        return true;
    }

    private String emitStrCall(String fn, String ret, String self) {
        String r = nv("v");
        body.append("  ").append(r).append(" = call ").append(ret).append(" @").append(fn)
            .append("(i64 ").append(self).append(")\n");
        if ("i32".equals(ret)) {
            pushInt(r);
        } else if ("i64".equals(ret)) {
            pushRef(r);
        }
        return r;
    }

    private String emitStrCall2(String fn, String ret, String a, String at, String b) {
        String r = nv("v");
        body.append("  ").append(r).append(" = call ").append(ret).append(" @").append(fn)
            .append("(i64 ").append(a).append(", ").append(at).append(" ").append(b).append(")\n");
        if ("i32".equals(ret)) {
            pushInt(r);
        } else if ("i64".equals(ret)) {
            pushRef(r);
        }
        return r;
    }

    private boolean invokeObjectMethod(MethodInsnNode m) {
        String d = m.desc;
        String key = m.name + d;
        if ("intValue()I".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_box_int(i64 ").append(self).append(")\n");
            pushInt(r);
            return true;
        }
        if ("longValue()J".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_long_box_get(i64 ").append(self).append(")\n");
            pushLong(r);
            return true;
        }
        if ("doubleValue()D".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_box_int(i64 ").append(self).append(")\n");
            String dv = nv("v");
            body.append("  ").append(dv).append(" = sitofp i32 ").append(r).append(" to double\n");
            pushDouble(dv);
            return true;
        }
        if ("floatValue()F".equals(key)) {
            String self = popRef();
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_box_int(i64 ").append(self).append(")\n");
            String fv = nv("v");
            body.append("  ").append(fv).append(" = sitofp i32 ").append(r).append(" to float\n");
            pushFloat(fv);
            return true;
        }
        if ("getSuperclass()Ljava/lang/Class;".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_class_get_superclass(i32)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_class_get_superclass(i32 ").append(cid).append(")\n");
            pushRef(r);
            return true;
        }
        if ("isInterface()Z".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i32 @aot_class_is_interface(i32)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_class_is_interface(i32 ").append(cid).append(")\n");
            pushInt(r);
            return true;
        }
        if ("isAssignableFrom(Ljava/lang/Class;)Z".equals(key)) {
            String other = popRef();
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i32 @aot_class_is_assignable_from(i32, i32)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String oid = nv("v");
            body.append("  ").append(oid).append(" = call i32 @aot_class_id(i64 ").append(other).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_class_is_assignable_from(i32 ").append(cid)
                .append(", i32 ").append(oid).append(")\n");
            pushInt(r);
            return true;
        }
        if ("getDeclaredFields()[Ljava/lang/reflect/Field;".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_class_declared_fields(i32)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_class_declared_fields(i32 ").append(cid).append(")\n");
            pushRef(r);
            return true;
        }
        if ("getDeclaredMethods()[Ljava/lang/reflect/Method;".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_class_declared_methods(i32)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_class_declared_methods(i32 ").append(cid).append(")\n");
            pushRef(r);
            return true;
        }
        if ("getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;".equals(key)) {
            String arr = popRef();
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_ctor_desc_of(i64)");
            declares.add("declare i64 @aot_ctor_lookup(i32, i64)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String dv = nv("v");
            body.append("  ").append(dv).append(" = call i64 @aot_ctor_desc_of(i64 ").append(arr).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_ctor_lookup(i32 ").append(cid)
                .append(", i64 ").append(dv).append(")\n");
            pushRef(r);
            return true;
        }
        if ("newInstance([Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
            String arr = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_object_array_ptr(i64)");
            declares.add("declare i64 @aot_ctor_new_instance(i64, i64)");
            String ap = nv("v");
            body.append("  ").append(ap).append(" = call i64 @aot_object_array_ptr(i64 ").append(arr).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_ctor_new_instance(i64 ").append(self)
                .append(", i64 ").append(ap).append(")\n");
            pushRef(r);
            return true;
        }
        if ("getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(key)) {
            String arr = popRef();
            String nm = popRef();
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_method_lookup(i32, i64)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_method_lookup(i32 ").append(cid)
                .append(", i64 ").append(nm).append(")\n");
            pushRef(r);
            return true;
        }
        if ("invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
            String arr = popRef();
            String target = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_object_array_ptr(i64)");
            declares.add("declare i64 @aot_method_invoke(i64, i64, i64)");
            String ap = nv("v");
            body.append("  ").append(ap).append(" = call i64 @aot_object_array_ptr(i64 ").append(arr).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_method_invoke(i64 ").append(self)
                .append(", i64 ").append(target).append(", i64 ").append(ap).append(")\n");
            pushRef(r);
            return true;
        }
        if ("getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(key)) {
            String nm = popRef();
            String self = popRef();
            declares.add("declare i32 @aot_class_id(i64)");
            declares.add("declare i64 @aot_field_lookup(i32, i64)");
            String cid = nv("v");
            body.append("  ").append(cid).append(" = call i32 @aot_class_id(i64 ").append(self).append(")\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_field_lookup(i32 ").append(cid)
                .append(", i64 ").append(nm).append(")\n");
            pushRef(r);
            return true;
        }
        if ("get(Ljava/lang/Object;)Ljava/lang/Object;".equals(key)) {
            String target = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_field_get_boxed(i64, i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_field_get_boxed(i64 ").append(self)
                .append(", i64 ").append(target).append(")\n");
            pushRef(r);
            return true;
        }
        if ("set(Ljava/lang/Object;Ljava/lang/Object;)V".equals(key)) {
            String val = popRef();
            String target = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_field_set(i64, i64, i64)");
            body.append("  call i64 @aot_field_set(i64 ").append(self).append(", i64 ").append(target)
                .append(", i64 ").append(val).append(")\n");
            return true;
        }
        if ("setInt(Ljava/lang/Object;I)V".equals(key)) {
            String val = popInt();
            String target = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_field_set(i64, i64, i64)");
            String v64 = nv("v");
            body.append("  ").append(v64).append(" = sext i32 ").append(val).append(" to i64\n");
            body.append("  call i64 @aot_field_set(i64 ").append(self).append(", i64 ").append(target)
                .append(", i64 ").append(v64).append(")\n");
            return true;
        }
        if ("getInt(Ljava/lang/Object;)I".equals(key)) {
            String target = popRef();
            String self = popRef();
            declares.add("declare i64 @aot_field_get(i64, i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_field_get(i64 ").append(self)
                .append(", i64 ").append(target).append(")\n");
            String i32v = nv("v");
            body.append("  ").append(i32v).append(" = trunc i64 ").append(r).append(" to i32\n");
            pushInt(i32v);
            return true;
        }
        if ("getName()Ljava/lang/String;".equals(key)) {
            String self = popRef();
            declares.add("declare i64 @aot_class_get_name(i64)");
            declares.add("declare i64 @aot_field_name(i64)");
            declares.add("declare i64 @aot_method_name(i64)");
            String r = nv("v");
            if ("java/lang/reflect/Field".equals(m.owner)) {
                body.append("  ").append(r).append(" = call i64 @aot_field_name(i64 ").append(self).append(")\n");
            } else if ("java/lang/reflect/Method".equals(m.owner)) {
                body.append("  ").append(r).append(" = call i64 @aot_method_name(i64 ").append(self).append(")\n");
            } else {
                body.append("  ").append(r).append(" = call i64 @aot_class_get_name(i64 ").append(self).append(")\n");
            }
            pushRef(r);
            return true;
        }
        if ("getSimpleName()Ljava/lang/String;".equals(key)) {
            String self = popRef();
            declares.add("declare i64 @aot_class_get_name(i64)");
            declares.add("declare i64 @aot_str_simple_name(i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_class_get_name(i64 ").append(self).append(")\n");
            String s = nv("v");
            body.append("  ").append(s).append(" = call i64 @aot_str_simple_name(i64 ").append(r).append(")\n");
            pushRef(s);
            return true;
        }
        if ("hashCode()I".equals(key)) {
            String self = popRef();
            emitStrCall("aot_object_hash", "i32", self);
            return true;
        }
        if ("wait()V".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_monitor_wait(i64, i64, i32)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_monitor_wait(i64 ").append(self)
                .append(", i64 0, i32 0)\n");
            emitIllegalMonitor(r);
            return true;
        }
        if ("wait(J)V".equals(key)) {
            String ms = popLong();
            String self = popRef();
            declares.add("declare i32 @aot_monitor_wait(i64, i64, i32)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_monitor_wait(i64 ").append(self)
                .append(", i64 ").append(ms).append(", i32 1)\n");
            emitIllegalMonitor(r);
            return true;
        }
        if ("wait(JI)V".equals(key)) {
            String ns = popInt();
            String ms = popLong();
            String self = popRef();
            declares.add("declare i32 @aot_monitor_wait(i64, i64, i32)");
            String total = nv("v");
            String nsl = nv("v");
            body.append("  ").append(nsl).append(" = sext i32 ").append(ns).append(" to i64\n");
            body.append("  ").append(total).append(" = add i64 ").append(ms).append(", 1\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_monitor_wait(i64 ").append(self)
                .append(", i64 ").append(total).append(", i32 1)\n");
            emitIllegalMonitor(r);
            return true;
        }
        if ("notify()V".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_monitor_notify(i64, i32)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_monitor_notify(i64 ").append(self).append(", i32 0)\n");
            emitIllegalMonitor(r);
            return true;
        }
        if ("notifyAll()V".equals(key)) {
            String self = popRef();
            declares.add("declare i32 @aot_monitor_notify(i64, i32)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i32 @aot_monitor_notify(i64 ").append(self).append(", i32 1)\n");
            emitIllegalMonitor(r);
            return true;
        }
        if ("equals(Ljava/lang/Object;)Z".equals(m.name + d)) {
            String other = popRef();
            String self = popRef();
            String eq = nv("v");
            body.append("  ").append(eq).append(" = icmp eq i64 ").append(self).append(", ").append(other).append("\n");
            String zi = nv("v");
            body.append("  ").append(zi).append(" = zext i1 ").append(eq).append(" to i32\n");
            pushInt(zi);
            return true;
        }
        if ("getClass()Ljava/lang/Class;".equals(m.name + d)) {
            String self = popRef();
            declares.add("declare i64 @aot_class_of(i64)");
            String r = nv("v");
            body.append("  ").append(r).append(" = call i64 @aot_class_of(i64 ").append(self).append(")\n");
            pushRef(r);
            return true;
        }
        return false;
    }

    private String retLlvm(String desc) {
        String k = TypeInfo.returnKind(desc);
        return switch (k) {
            case "V" -> "i32";
            case "I", "F" -> "i32";
            default -> "i64";
        };
    }

    private void pushReturnValue(String desc, String reg) {
        String k = TypeInfo.returnKind(desc);
        switch (k) {
            case "V" -> {
            }
            case "I" -> pushInt(reg);
            case "F" -> pushFloat(reg);
            case "D" -> {
                String asD = nv("v");
                body.append("  ").append(asD).append(" = bitcast i64 ").append(reg).append(" to double\n");
                pushDouble(asD);
            }
            case "L" -> pushRef(reg);
            default -> pushLong(reg);
        }
    }

    private void pushReturnPlaceholder(String desc) {
        String k = TypeInfo.returnKind(desc);
        switch (k) {
            case "V" -> {
            }
            case "I" -> pushIntLit(0);
            case "F" -> pushFloatLit(0f);
            case "D" -> pushDoubleLit(0.0);
            case "L" -> pushIntLit(0);
            default -> pushLongLit(0L);
        }
    }

    private void invokeNative(Natives.Sig sig, MethodInsnNode m) {
        String[] args = new String[sig.argCount];
        for (int i = sig.argCount - 1; i >= 0; i--) {
            args[i] = pop();
        }
        boolean isStaticCall = m.getOpcode() == Opcodes.INVOKESTATIC;
        if (!isStaticCall) {
            pop();
        }
        if ("print_str".equals(sig.cName) || "print_str_raw".equals(sig.cName)) {
            declares.add("declare void @aot_str_print(i64)");
            declares.add("declare i32 @putchar(i32)");
            String sv = nv("r");
            body.append("  call void @aot_str_print(i64 ").append(args[0]).append(")\n");
            if ("print_str".equals(sig.cName)) {
                body.append("  ").append(sv).append(" = call i32 @putchar(i32 10)\n");
            }
        } else if ("print_int".equals(sig.cName) || "print_int_raw".equals(sig.cName)) {
            String fmt = pool.internFormat("print_int".equals(sig.cName) ? "%d\\0A" : "%d");
            String argKind = TypeInfo.kindOf(org.objectweb.asm.Type.getType(m.desc).getArgumentTypes()[0]);
            String iv;
            if ("J".equals(argKind)) {
                iv = nv("v");
                body.append("  ").append(iv).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            } else if ("D".equals(argKind)) {
                String dd = nv("v");
                body.append("  ").append(dd).append(" = bitcast i64 ").append(args[0]).append(" to double\n");
                iv = nv("v");
                body.append("  ").append(iv).append(" = fptosi double ").append(dd).append(" to i32\n");
            } else if ("F".equals(argKind)) {
                String ff = nv("v");
                body.append("  ").append(ff).append(" = bitcast i32 ").append(args[0]).append(" to float\n");
                iv = nv("v");
                body.append("  ").append(iv).append(" = fptosi float ").append(ff).append(" to i32\n");
            } else {
                iv = nv("v");
                body.append("  ").append(iv).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            }
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", i32 ").append(iv).append(")\n");
        } else if ("print_long".equals(sig.cName) || "print_long_raw".equals(sig.cName)) {
            String fmt = pool.internFormat("print_long".equals(sig.cName) ? "%ld\\0A" : "%ld");
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", i64 ").append(args[0]).append(")\n");
        } else if ("print_double".equals(sig.cName) || "print_double_raw".equals(sig.cName)) {
            String fmt = pool.internFormat("print_double".equals(sig.cName) ? "%g\\0A" : "%g");
            String dd = nv("v");
            body.append("  ").append(dd).append(" = bitcast i64 ").append(args[0]).append(" to double\n");
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", double ").append(dd).append(")\n");
        } else if ("print_float".equals(sig.cName) || "print_float_raw".equals(sig.cName)) {
            String fmt = pool.internFormat("print_float".equals(sig.cName) ? "%g\\0A" : "%g");
            String ff = nv("v");
            body.append("  ").append(ff).append(" = bitcast i32 ").append(args[0]).append(" to float\n");
            String fd = nv("v");
            body.append("  ").append(fd).append(" = fpext float ").append(ff).append(" to double\n");
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", double ").append(fd).append(")\n");
        } else if ("print_obj".equals(sig.cName)) {
            declares.add("declare void @aot_print_object(i64)");
            declares.add("declare i32 @putchar(i32)");
            body.append("  call void @aot_print_object(i64 ").append(args[0]).append(")\n");
            body.append("  ").append(nv("r")).append(" = call i32 @putchar(i32 10)\n");
        } else if ("print_char".equals(sig.cName) || "print_char_raw".equals(sig.cName)) {
            declares.add("declare void @aot_put_char(i32)");
            declares.add("declare i32 @putchar(i32)");
            String cv = nv("v");
            body.append("  ").append(cv).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            body.append("  call void @aot_put_char(i32 ").append(cv).append(")\n");
            if ("print_char".equals(sig.cName)) {
                body.append("  ").append(nv("r")).append(" = call i32 @putchar(i32 10)\n");
            }
        } else if ("print_bool".equals(sig.cName)) {
            String fmt = pool.internFormat("%d\\0A");
            String bv = nv("v");
            body.append("  ").append(bv).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            String nz = nv("v");
            body.append("  ").append(nz).append(" = icmp ne i32 ").append(bv).append(", 0\n");
            String zi = nv("v");
            body.append("  ").append(zi).append(" = zext i1 ").append(nz).append(" to i32\n");
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", i32 ").append(zi).append(")\n");
        } else if ("print_nl".equals(sig.cName)) {
            String fmt = pool.internFormat("\\0A");
            declares.add("declare i32 @printf(ptr, ...)");
            body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(")\n");
        } else if ("math_abs".equals(sig.cName)) {
            String x = nv("v");
            body.append("  ").append(x).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            String neg = nv("v");
            body.append("  ").append(neg).append(" = sub i32 0, ").append(x).append("\n");
            String c = nv("c");
            body.append("  ").append(c).append(" = icmp slt i32 ").append(x).append(", 0\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = select i1 ").append(c).append(", i32 ").append(neg).append(", i32 ").append(x).append("\n");
            pushInt(r);
        } else if ("math_max".equals(sig.cName) || "math_min".equals(sig.cName)) {
            String a = nv("v");
            body.append("  ").append(a).append(" = trunc i64 ").append(args[0]).append(" to i32\n");
            String b = nv("v");
            body.append("  ").append(b).append(" = trunc i64 ").append(args[1]).append(" to i32\n");
            String c = nv("c");
            body.append("  ").append(c).append(" = icmp ").append("math_max".equals(sig.cName) ? "sgt" : "slt").append(" i32 ").append(a).append(", ").append(b).append("\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = select i1 ").append(c).append(", i32 ").append(a).append(", i32 ").append(b).append("\n");
            pushInt(r);
        } else {
            fail("unregistered native " + sig.cName);
        }
    }

    private void ifSingle(int op, JumpInsnNode j) {
        String x = popInt();
        String pred = switch (op) {
            case Opcodes.IFEQ -> "eq";
            case Opcodes.IFNE -> "ne";
            case Opcodes.IFLT -> "slt";
            case Opcodes.IFGE -> "sge";
            case Opcodes.IFGT -> "sgt";
            default -> "sle";
        };
        String c = nv("c");
        body.append("  ").append(c).append(" = icmp ").append(pred).append(" i32 ").append(x).append(", 0\n");
        branchTo(j, c);
    }

    private void ifCompare(int op, JumpInsnNode j) {
        String b = popInt();
        String a = popInt();
        String pred = switch (op) {
            case Opcodes.IF_ICMPEQ -> "eq";
            case Opcodes.IF_ICMPNE -> "ne";
            case Opcodes.IF_ICMPLT -> "slt";
            case Opcodes.IF_ICMPGE -> "sge";
            case Opcodes.IF_ICMPGT -> "sgt";
            default -> "sle";
        };
        String c = nv("c");
        body.append("  ").append(c).append(" = icmp ").append(pred).append(" i32 ").append(a).append(", ").append(b).append("\n");
        branchTo(j, c);
    }

    private void ifRefNull(boolean jumpWhenNull, JumpInsnNode j) {
        String v = popRef();
        String c = nv("c");
        body.append("  ").append(c).append(" = icmp ").append(jumpWhenNull ? "eq" : "ne").append(" i64 ").append(v).append(", 0\n");
        branchTo(j, c);
    }

    private void ifRefCompare(boolean jumpWhenEqual, JumpInsnNode j) {
        String b = popRef();
        String a = popRef();
        String c = nv("c");
        body.append("  ").append(c).append(" = icmp ").append(jumpWhenEqual ? "eq" : "ne").append(" i64 ").append(a).append(", ").append(b).append("\n");
        branchTo(j, c);
    }

    private void branchTo(JumpInsnNode j, String cond) {
        String t = label(j.label);
        String f = "fall" + (tmp++);
        body.append("  br i1 ").append(cond).append(", label %").append(t).append(", label %").append(f).append("\n");
        body.append(f).append(":\n");
        terminated = false;
    }

    private void pushIntLit(int v) {
        String e = nv("e");
        body.append("  ").append(e).append(" = sext i32 ").append(v).append(" to i64\n");
        pushSlot(new Slot("I", e));
    }

    private void pushLongLit(long v) {
        pushLong(String.valueOf(v));
    }

    private void pushFloatLit(float v) {
        pushFloatBits(v);
    }

    private void pushDoubleLit(double v) {
        pushDoubleBits(v);
    }

    private void pushDoubleBits(double v) {
        String b = nv("d");
        body.append("  ").append(b).append(" = bitcast i64 ").append(Double.doubleToRawLongBits(v)).append(" to double\n");
        pushSlot(new Slot("D", b));
    }



    private String floatBitsLit(float v) {
        return String.valueOf(Float.floatToRawIntBits(v));
    }

    private void pushFloatBits(float v) {
        String b = nv("f");
        body.append("  ").append(b).append(" = bitcast i32 ").append(floatBitsLit(v)).append(" to float\n");
        pushSlot(new Slot("F", b));
    }

    private void pushInt(String v32) {
        String e = nv("e");
        body.append("  ").append(e).append(" = sext i32 ").append(v32).append(" to i64\n");
        pushSlot(new Slot("I", e));
    }

    private void pushInt(String v32, String alreadyReg) {
        String e = nv("e");
        body.append("  ").append(e).append(" = sext i32 ").append(v32).append(" to i64\n");
        pushSlot(new Slot("I", e));
    }

    private void pushInt64AsInt(String v64) {
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v64).append(" to i32\n");
        pushInt(t);
    }

    private void pushLong(String v64) {
        String e = nv("e");
        body.append("  ").append(e).append(" = add i64 ").append(v64).append(", 0\n");
        pushSlot(new Slot("J", e));
    }

    private void pushFloat(String lit) {
        pushSlot(new Slot("F", lit));
    }

    private void pushDouble(String lit) {
        pushSlot(new Slot("D", lit));
    }

    private void pushRef(String v64) {
        pushSlot(new Slot("L", v64));
    }

    private void pushStringLit(String s) {
        String slot = pool.slotFor(s);
        if (slot == null) {
            pool.internUtf16(s);
            slot = pool.slotFor(s);
        }
        String v = nv("s");
        body.append("  ").append(v).append(" = load i64, ptr ").append(slot).append("\n");
        pushRef(v);
    }

    private void pushIntFromLocal(int idx) {
        String v = loadLocal(idx);
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v).append(" to i32\n");
        pushInt(t);
    }

    private void pushLongFromLocal(int idx) {
        pushLong(loadLocal(idx));
    }

    private void pushFloatFromLocal(int idx) {
        String v = loadLocal(idx);
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v).append(" to i32\n");
        String b = nv("f");
        body.append("  ").append(b).append(" = bitcast i32 ").append(t).append(" to float\n");
        pushSlot(new Slot("F", b));
    }

    private void pushDoubleFromLocal(int idx) {
        String v = loadLocal(idx);
        String b = nv("d");
        body.append("  ").append(b).append(" = bitcast i64 ").append(v).append(" to double\n");
        pushSlot(new Slot("D", b));
    }

    private void pushRefFromLocal(int idx) {
        pushRef(loadLocal(idx));
    }

    private String localPtr(int idx) {
        String p = nv("lp");
        body.append("  ").append(p).append(" = getelementptr [").append(localsSize).append(" x i64], ptr %locals, i64 0, i32 ").append(idx).append("\n");
        return p;
    }

    private String loadLocal(int idx) {
        String p = localPtr(idx);
        String v = nv("lv");
        body.append("  ").append(v).append(" = load i64, ptr ").append(p).append("\n");
        return v;
    }

    private void storeLocal(int idx, boolean wide) {
        String k = stack.isEmpty() ? stackKindAt(0) : stack.get(stack.size() - 1).kind;
        Slot raw = popSlot();
        String s = raw.value;
        String v64;
        if ("I".equals(k)) {
            String t = nv("s");
            body.append("  ").append(t).append(" = trunc i64 ").append(s).append(" to i32\n");
            String e = nv("s");
            body.append("  ").append(e).append(" = sext i32 ").append(t).append(" to i64\n");
            v64 = e;
        } else if ("F".equals(k)) {
            String back = nv("s");
            body.append("  ").append(back).append(" = trunc i64 ").append(s).append(" to i32\n");
            String e = nv("s");
            body.append("  ").append(e).append(" = sext i32 ").append(back).append(" to i64\n");
            v64 = e;
        } else {
            v64 = s;
        }
        String p = localPtr(idx);
        body.append("  store i64 ").append(v64).append(", ptr ").append(p).append("\n");
        if (wide) {
            String p2 = localPtr(idx + 1);
            body.append("  store i64 0, ptr ").append(p2).append("\n");
        }
    }

    private void pushSlot(Slot s) {
        String d = nv("d");
        body.append("  ").append(d).append(" = load i32, ptr %sp\n");
        String enc = encode(s);
        String slot = nv("sl");
        body.append("  ").append(slot).append(" = getelementptr [").append(STACK_SLOTS).append(" x i64], ptr %stack, i64 0, i32 ").append(d).append("\n");
        body.append("  store i64 ").append(enc).append(", ptr ").append(slot).append("\n");
        String n = nv("n");
        body.append("  ").append(n).append(" = add i32 ").append(d).append(", 1\n");
        body.append("  store i32 ").append(n).append(", ptr %sp\n");
        stack.add(s);
    }

    private String encode(Slot s) {
        return switch (s.kind) {
            case "F" -> {
                String t = nv("k");
                body.append("  ").append(t).append(" = bitcast float ").append(s.value).append(" to i32\n");
                String e = nv("k");
                body.append("  ").append(e).append(" = sext i32 ").append(t).append(" to i64\n");
                yield e;
            }
            case "D" -> {
                String t = nv("k");
                body.append("  ").append(t).append(" = bitcast double ").append(s.value).append(" to i64\n");
                yield t;
            }
            default -> s.value;
        };
    }

    private Slot popSlot() {
        String d = nv("d");
        body.append("  ").append(d).append(" = load i32, ptr %sp\n");
        String d0 = nv("d");
        body.append("  ").append(d0).append(" = sub i32 ").append(d).append(", 1\n");
        body.append("  store i32 ").append(d0).append(", ptr %sp\n");
        String slot = nv("sl");
        body.append("  ").append(slot).append(" = getelementptr [").append(STACK_SLOTS).append(" x i64], ptr %stack, i64 0, i32 ").append(d0).append("\n");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i64, ptr ").append(slot).append("\n");
        Slot s = stack.isEmpty() ? new Slot("I", v) : stack.remove(stack.size() - 1);
        return new Slot(s.kind, v);
    }

    private String pop() {
        return popSlot().value;
    }

    private String popInt() {
        Slot s = popSlot();
        if ("I".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = trunc i64 ").append(s.value).append(" to i32\n");
            return t;
        }
        if ("F".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = fptosi float ").append(s.value).append(" to i32\n");
            return t;
        }
        if ("D".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = fptosi double ").append(s.value).append(" to i32\n");
            return t;
        }
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(s.value).append(" to i32\n");
        return t;
    }

    private String popLong() {
        Slot s = popSlot();
        if ("D".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = fptosi double ").append(s.value).append(" to i64\n");
            return t;
        }
        if ("F".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = fptosi float ").append(s.value).append(" to i64\n");
            return t;
        }
        if ("I".equals(s.kind)) {
            String v64 = nv("v");
            body.append("  ").append(v64).append(" = add i64 ").append(s.value).append(", 0\n");
            String t32 = nv("v");
            body.append("  ").append(t32).append(" = trunc i64 ").append(v64).append(" to i32\n");
            String r = nv("v");
            body.append("  ").append(r).append(" = sext i32 ").append(t32).append(" to i64\n");
            return r;
        }
        String t = nv("v");
        body.append("  ").append(t).append(" = add i64 ").append(s.value).append(", 0\n");
        return t;
    }

    private String popFloat() {
        Slot s = popSlot();
        String raw = s.value;
        if ("F".equals(s.kind)) {
            String back = nv("v");
            body.append("  ").append(back).append(" = trunc i64 ").append(raw).append(" to i32\n");
            String f = nv("v");
            body.append("  ").append(f).append(" = bitcast i32 ").append(back).append(" to float\n");
            return f;
        }
        if ("D".equals(s.kind)) {
            String bd = nv("v");
            body.append("  ").append(bd).append(" = bitcast i64 ").append(raw).append(" to double\n");
            String f = nv("v");
            body.append("  ").append(f).append(" = fptrunc double ").append(bd).append(" to float\n");
            return f;
        }
        String i32v = nv("v");
        body.append("  ").append(i32v).append(" = trunc i64 ").append(raw).append(" to i32\n");
        String f = nv("v");
        body.append("  ").append(f).append(" = sitofp i32 ").append(i32v).append(" to float\n");
        return f;
    }

    private String popDouble() {
        Slot s = popSlot();
        String raw = s.value;
        String asDouble = nv("v");
        body.append("  ").append(asDouble).append(" = bitcast i64 ").append(raw).append(" to double\n");
        if ("D".equals(s.kind)) {
            return asDouble;
        }
        if ("F".equals(s.kind)) {
            String back = nv("v");
            body.append("  ").append(back).append(" = trunc i64 ").append(raw).append(" to i32\n");
            String f = nv("v");
            body.append("  ").append(f).append(" = bitcast i32 ").append(back).append(" to float\n");
            String d = nv("v");
            body.append("  ").append(d).append(" = fpext float ").append(f).append(" to double\n");
            return d;
        }
        String i32v = nv("v");
        body.append("  ").append(i32v).append(" = trunc i64 ").append(raw).append(" to i32\n");
        String d = nv("v");
        body.append("  ").append(d).append(" = sitofp i32 ").append(i32v).append(" to double\n");
        return d;
    }

    private String popRef() {
        Slot s = popSlot();
        if ("L".equals(s.kind) || "J".equals(s.kind)) {
            String t = nv("v");
            body.append("  ").append(t).append(" = add i64 ").append(s.value).append(", 0\n");
            return t;
        }
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(s.value).append(" to i32\n");
        String e = nv("v");
        body.append("  ").append(e).append(" = sext i32 ").append(t).append(" to i64\n");
        return e;
    }

    private String popOfKind(String kind) {
        switch (kind) {
            case "I":
                return popInt();
            case "J":
                return popLong();
            case "F": {
                String f = popFloat();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast float ").append(f).append(" to i32\n");
                return bits;
            }
            case "D": {
                String d = popDouble();
                String bits = nv("v");
                body.append("  ").append(bits).append(" = bitcast double ").append(d).append(" to i64\n");
                return bits;
            }
            default:
                return popRef();
        }
    }

    private String toPtr(String v64) {
        String t = nv("p");
        body.append("  ").append(t).append(" = inttoptr i64 ").append(v64).append(" to ptr\n");
        return t;
    }

    private String label(LabelNode l) {
        String existing = labels.get(l);
        if (existing != null) {
            return existing;
        }
        String name = "Lb" + (labelSeq++);
        labels.put(l, name);
        return name;
    }

    private String nv(String base) {
        return "%" + base + (tmp++);
    }

    private void fail(String msg) {
        throw new AotException(msg);
    }
}