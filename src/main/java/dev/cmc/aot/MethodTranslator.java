package dev.cmc.aot;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
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

    private MethodTranslator(ClassInfo owner, MethodNode mn, StringPool pool, Set<String> declares, Layout layout, Frame<BasicValue>[] frames) {
        this.owner = owner;
        this.mn = mn;
        this.pool = pool;
        this.declares = declares;
        this.layout = layout;
        this.localsSize = Math.max(mn.maxLocals, 2);
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
        String retLlvm = TypeInfo.llvm(TypeInfo.returnKind(mn.desc));
        String retType = "V".equals(TypeInfo.returnKind(mn.desc)) ? "i32" : retLlvm;
        body.append("define ").append(retType).append(" @")
            .append(ClassParser.mangle(owner.name(), mn.name, mn.desc))
            .append("(").append(sig).append(") {\n");
        body.append("entry:\n");
        body.append("  %locals = alloca [").append(localsSize).append(" x i64]\n");
        body.append("  %stack = alloca [").append(STACK_SLOTS).append(" x i64]\n");
        body.append("  %sp = alloca i32\n");
        body.append("  store i32 0, ptr %sp\n");

        int slot = 0;
        if (isInstance) {
            String tp = localPtr(0);
            body.append("  store i64 %this, ptr ").append(tp).append("\n");
            slot = 1;
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
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            insnIndex = insns.indexOf(insn);
            if (terminated && !(insn instanceof LabelNode)) {
                body.append("  ").append(nv("dead")).append(":\n");
                terminated = false;
            }
            translateInsn(insn);
        }

        if (!terminated) {
            body.append("  ret ").append(retType).append(" ").append("i32".equals(retType) ? "0" : "0").append("\n");
        }
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
                body.append("  ret i32 0\n");
                terminated = true;
            }
            case Opcodes.IRETURN -> {
                String v = popInt();
                body.append("  ret i32 ").append(v).append("\n");
                terminated = true;
            }
            case Opcodes.LRETURN -> {
                String v = popLong();
                body.append("  ret i64 ").append(v).append("\n");
                terminated = true;
            }
            case Opcodes.FRETURN -> {
                String v = popFloat();
                body.append("  ret float ").append(v).append("\n");
                terminated = true;
            }
            case Opcodes.DRETURN -> {
                String v = popDouble();
                body.append("  ret double ").append(v).append("\n");
                terminated = true;
            }
            case Opcodes.ARETURN -> {
                String v = popRef();
                body.append("  ret i64 ").append(v).append("\n");
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
                String r = nv("v");
                body.append("  ").append(r).append(" = fptosi float ").append(a).append(" to i32\n");
                pushInt(r);
            }
            case Opcodes.F2L -> {
                String a = popFloat();
                String r = nv("v");
                body.append("  ").append(r).append(" = fptosi float ").append(a).append(" to i64\n");
                pushLong(r);
            }
            case Opcodes.F2D -> {
                String a = popFloat();
                String r = nv("v");
                body.append("  ").append(r).append(" = fpext float ").append(a).append(" to double\n");
                pushDouble(r);
            }
            case Opcodes.D2I -> {
                String a = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = fptosi double ").append(a).append(" to i32\n");
                pushInt(r);
            }
            case Opcodes.D2L -> {
                String a = popDouble();
                String r = nv("v");
                body.append("  ").append(r).append(" = fptosi double ").append(a).append(" to i64\n");
                pushLong(r);
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
            case Opcodes.NEW -> newObject((TypeInsnNode) insn);
            case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> newArray();
            case Opcodes.ARRAYLENGTH -> {
                String av = popRef();
                String ap = toPtr(av);
                String len = nv("al");
                body.append("  ").append(len).append(" = load i64, ptr ").append(ap).append("\n");
                pushInt64AsInt(len);
            }
            case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> arrayLoadI32();
            case Opcodes.LALOAD -> arrayLoadI64();
            case Opcodes.FALOAD -> arrayLoadF32();
            case Opcodes.DALOAD -> arrayLoadI64();
            case Opcodes.AALOAD -> arrayLoadI64();
            case Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> arrayStoreI32();
            case Opcodes.LASTORE, Opcodes.DASTORE, Opcodes.AASTORE -> arrayStoreI64();
            case Opcodes.FASTORE -> arrayStoreF32();
            case Opcodes.GETFIELD -> getField((FieldInsnNode) insn);
            case Opcodes.PUTFIELD -> putField((FieldInsnNode) insn);
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (Natives.isSystemOut(f.owner, f.name)) {
                    pushIntLit(0);
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
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(intArithLlvm(op)).append(" i32 ").append(a).append(", ").append(b).append("\n");
        pushInt(r);
    }

    private void intDivRem(int op) {
        String b = popInt();
        String a = popInt();
        String kind = op == Opcodes.IDIV ? "sdiv" : "srem";
        String zc = nv("c");
        body.append("  ").append(zc).append(" = icmp eq i32 ").append(b).append(", 0\n");
        String bb = nv("b");
        body.append("  ").append(bb).append(" = select i1 ").append(zc).append(", i32 1, i32 ").append(b).append("\n");
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(kind).append(" i32 ").append(a).append(", ").append(bb).append("\n");
        pushInt(r);
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
            String s64 = nv("s");
            body.append("  ").append(s64).append(" = sext i32 ").append(sh).append(" to i64\n");
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
        String kind = op == Opcodes.LDIV ? "sdiv" : "srem";
        String zc = nv("c");
        body.append("  ").append(zc).append(" = icmp eq i64 ").append(b).append(", 0\n");
        String bb = nv("b");
        body.append("  ").append(bb).append(" = select i1 ").append(zc).append(", i64 1, i64 ").append(b).append("\n");
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(kind).append(" i64 ").append(a).append(", ").append(bb).append("\n");
        pushLong(r);
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

    private void newObject(TypeInsnNode tn) {
        String cls = tn.desc;
        if (!layout.hasClass(cls)) {
            fail("unknown class for new: " + cls);
        }
        declares.add("declare ptr @calloc(i64, i64)");
        int size = layout.objectSize(cls);
        String raw = nv("np");
        body.append("  ").append(raw).append(" = call ptr @calloc(i64 ").append(size).append(", i64 1)\n");
        body.append("  store ptr ").append(layout.vtableLabel(cls)).append(", ptr ").append(raw).append("\n");
        String iv = nv("nv");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(raw).append(" to i64\n");
        pushRef(iv);
    }

    private void newArray() {
        String cnt = popInt();
        declares.add("declare ptr @calloc(i64, i64)");
        String c64 = nv("an");
        body.append("  ").append(c64).append(" = sext i32 ").append(cnt).append(" to i64\n");
        String bytes = nv("ab");
        body.append("  ").append(bytes).append(" = mul i64 ").append(c64).append(", 8\n");
        String total = nv("ab");
        body.append("  ").append(total).append(" = add i64 ").append(bytes).append(", 8\n");
        String raw = nv("ap");
        body.append("  ").append(raw).append(" = call ptr @calloc(i64 ").append(total).append(", i64 1)\n");
        body.append("  store i64 ").append(c64).append(", ptr ").append(raw).append("\n");
        String iv = nv("av");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(raw).append(" to i64\n");
        pushRef(iv);
    }

    private String arrayElemPtr(String kind) {
        String idx = popInt();
        String av = popRef();
        String ap = toPtr(av);
        String ety = switch (kind) {
            case "I" -> "i32";
            case "F" -> "float";
            default -> "i64";
        };
        String i64 = nv("ai");
        body.append("  ").append(i64).append(" = sext i32 ").append(idx).append(" to i64\n");
        String off = nv("ao");
        body.append("  ").append(off).append(" = add i64 ").append(i64).append(", 1\n");
        String ep = nv("aep");
        body.append("  ").append(ep).append(" = getelementptr ").append(ety).append(", ptr ").append(ap).append(", i64 ").append(off).append("\n");
        return ep;
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
        if (m.owner.startsWith("java/")) {
            body.append("  ; unimplemented jdk ctor ").append(m.owner).append('.').append(m.name).append("\n");
            pushReturnPlaceholder(m.desc);
            return;
        }
        String objp = objv;
        StringBuilder callArgs = new StringBuilder("i64 ").append(objp);
        for (int i = 0; i < kinds.size(); i++) {
            String ak = kinds.get(i);
            String at = "F".equals(ak) ? "i32" : TypeInfo.llvm(ak);
            callArgs.append(", ").append(at).append(" ").append(raw[i]);
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call ").append(retLlvm(m.desc)).append(" @")
            .append(ClassParser.mangle(m.owner, m.name, m.desc)).append("(").append(callArgs).append(")\n");
        pushReturnValue(m.desc, r);
    }

    private void invokeVirtual(MethodInsnNode m) {
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
        if (m.owner.startsWith("java/")) {
            body.append("  ; unimplemented jdk virtual ").append(m.owner).append('.').append(m.name).append("\n");
            pushReturnPlaceholder(m.desc);
            return;
        }
        String objp = toPtr(objv);
        int slot = layout.slotOf(m.name, m.desc);
        String vtp = nv("vt");
        body.append("  ").append(vtp).append(" = load ptr, ptr ").append(objp).append("\n");
        String fpp = nv("vf");
        body.append("  ").append(fpp).append(" = getelementptr ptr, ptr ").append(vtp).append(", i32 ").append(slot).append("\n");
        String fnp = nv("fn");
        body.append("  ").append(fnp).append(" = load ptr, ptr ").append(fpp).append("\n");
        StringBuilder callArgs = new StringBuilder("i64 ").append(objv);
        for (int i = 0; i < kinds.size(); i++) {
            String ak = kinds.get(i);
            String at = "F".equals(ak) ? "i32" : TypeInfo.llvm(ak);
            callArgs.append(", ").append(at).append(" ").append(raw[i]);
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call ").append(retLlvm(m.desc)).append(" ").append(fnp).append("(").append(callArgs).append(")\n");
        pushReturnValue(m.desc, r);
    }

    private void invoke(MethodInsnNode m) {
        Natives.Sig sig = Natives.lookup(m.owner, m.name, m.desc);
        if (sig != null) {
            invokeNative(sig, m);
            return;
        }
        List<String> kinds = TypeInfo.argKinds(m.desc);
        String[] raw = new String[kinds.size()];
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = popOfKind(kinds.get(i));
        }
        if (m.owner.startsWith("java/") || m.owner.startsWith("javax/")) {
            body.append("  ; unimplemented jdk call ").append(m.owner).append('.').append(m.name).append(m.desc).append('\n');
            pushReturnPlaceholder(m.desc);
            return;
        }
        StringBuilder callArgs = new StringBuilder();
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) {
                callArgs.append(", ");
            }
            callArgs.append(TypeInfo.llvm(kinds.get(i))).append(" ").append(raw[i]);
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call ").append(retLlvm(m.desc)).append(" @")
            .append(ClassParser.mangle(m.owner, m.name, m.desc)).append("(").append(callArgs).append(")\n");
        pushReturnValue(m.desc, r);
    }

    private String retLlvm(String desc) {
        String k = TypeInfo.returnKind(desc);
        return "V".equals(k) ? "i32" : TypeInfo.llvm(k);
    }

    private void pushReturnValue(String desc, String reg) {
        String k = TypeInfo.returnKind(desc);
        switch (k) {
            case "V" -> {
            }
            case "I" -> pushInt(reg);
            case "F" -> pushFloat(reg);
            case "D" -> pushDouble(reg);
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
            String pv = toPtr(args[0]);
            if ("print_str".equals(sig.cName)) {
                declares.add("declare i32 @puts(ptr)");
                body.append("  ").append(nv("r")).append(" = call i32 @puts(ptr ").append(pv).append(")\n");
            } else {
                String fmt = pool.internFormat("%s");
                declares.add("declare i32 @printf(ptr, ...)");
                body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", ptr ").append(pv).append(")\n");
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
        pushFloat(fmtFloat(v));
    }

    private void pushDoubleLit(double v) {
        pushDouble(fmtDouble(v));
    }

    private String fmtFloat(float v) {
        if (Float.isNaN(v)) {
            return "0x7FF80000";
        }
        if (Float.isInfinite(v)) {
            return v > 0 ? "0x7F800000" : "0xFF800000";
        }
        return String.valueOf(v);
    }

    private String fmtDouble(double v) {
        if (Double.isNaN(v)) {
            return "0x7FF8000000000000";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "0x7FF0000000000000" : "0xFFF0000000000000";
        }
        return String.valueOf(v);
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
        String g = pool.intern(s);
        String iv = nv("s");
        body.append("  ").append(iv).append(" = ptrtoint ptr ").append(g).append(" to i64\n");
        pushRef(iv);
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
        Slot s = popSlot();
        String v64;
        if ("I".equals(s.kind)) {
            String t = nv("s");
            body.append("  ").append(t).append(" = trunc i64 ").append(s.value).append(" to i32\n");
            String e = nv("s");
            body.append("  ").append(e).append(" = sext i32 ").append(t).append(" to i64\n");
            v64 = e;
        } else if ("F".equals(s.kind)) {
            String back = nv("s");
            body.append("  ").append(back).append(" = trunc i64 ").append(s.value).append(" to i32\n");
            String e = nv("s");
            body.append("  ").append(e).append(" = sext i32 ").append(back).append(" to i64\n");
            v64 = e;
        } else if ("D".equals(s.kind)) {
            v64 = s.value;
        } else {
            v64 = s.value;
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