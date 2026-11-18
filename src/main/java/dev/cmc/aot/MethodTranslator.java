package dev.cmc.aot;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MethodTranslator {

    private static final int STACK_SLOTS = 64;

    private final ClassInfo owner;
    private final MethodNode mn;
    private final StringPool pool;
    private final Set<String> declares;
    private final StringBuilder body = new StringBuilder();
    private final java.util.IdentityHashMap<LabelNode, String> labels = new java.util.IdentityHashMap<>();
    private int tmp;
    private int labelSeq;
    private boolean terminated;

    private MethodTranslator(ClassInfo owner, MethodNode mn, StringPool pool, Set<String> declares) {
        this.owner = owner;
        this.mn = mn;
        this.pool = pool;
        this.declares = declares;
    }

    public static String translate(ClassInfo owner, MethodNode mn, StringPool pool, Set<String> declares) {
        MethodTranslator t = new MethodTranslator(owner, mn, pool, declares);
        return t.run();
    }

    private String run() {
        int locals = Math.max(mn.maxLocals, 1);
        List<String> argKinds = TypeUtil.argKinds(mn.desc);
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < argKinds.size(); i++) {
            if (i > 0) {
                sig.append(", ");
            }
            sig.append("I".equals(argKinds.get(i)) ? "i32" : "i64").append(" %a").append(i);
        }
        body.append("define i32 @").append(ClassParser.mangle(owner.name(), mn.name, mn.desc)).append("(").append(sig).append(") {\n");
        body.append("entry:\n");
        body.append("  %locals = alloca [").append(locals).append(" x i64]\n");
        body.append("  %stack = alloca [").append(STACK_SLOTS).append(" x i64]\n");
        body.append("  %sp = alloca i32\n");
        body.append("  store i32 0, ptr %sp\n");

        int slot = ClassParser.isStatic(mn) ? 0 : 1;
        for (int i = 0; i < argKinds.size(); i++) {
            String p = nv("lp");
            body.append("  ").append(p).append(" = getelementptr [").append(locals).append(" x i64], ptr %locals, i64 0, i32 ").append(slot).append("\n");
            if ("I".equals(argKinds.get(i))) {
                String e = nv("e");
                body.append("  ").append(e).append(" = sext i32 %a").append(i).append(" to i64\n");
                body.append("  store i64 ").append(e).append(", ptr ").append(p).append("\n");
            } else {
                body.append("  store i64 %a").append(i).append(", ptr ").append(p).append("\n");
            }
            slot++;
        }

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (terminated) {
                if (insn.getOpcode() == Opcodes.LABEL) {
                    terminated = false;
                } else {
                    body.append("  ").append(nv("dead")).append(":\n");
                    terminated = false;
                }
            }
            translateInsn(insn);
        }

        if (!terminated) {
            body.append("  ret i32 0\n");
        }
        body.append("}\n");
        return body.toString();
    }

    private void translateInsn(AbstractInsnNode insn) {
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
            case Opcodes.ICONST_M1 -> pushIntLit(-1);
            case Opcodes.ICONST_0 -> pushIntLit(0);
            case Opcodes.ICONST_1 -> pushIntLit(1);
            case Opcodes.ICONST_2 -> pushIntLit(2);
            case Opcodes.ICONST_3 -> pushIntLit(3);
            case Opcodes.ICONST_4 -> pushIntLit(4);
            case Opcodes.ICONST_5 -> pushIntLit(5);
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> pushIntLit(((IntInsnNode) insn).operand);
            case Opcodes.LDC -> {
                LdcInsnNode n = (LdcInsnNode) insn;
                if (n.cst instanceof Integer i) {
                    pushIntLit(i);
                } else if (n.cst instanceof String s) {
                    pushStringLit(s);
                } else {
                    fail("unsupported ldc constant: " + n.cst);
                }
            }
            case Opcodes.ILOAD -> pushIntFromLocal(((VarInsnNode) insn).var);
            case Opcodes.ISTORE -> storeLocal(((VarInsnNode) insn).var);
            case Opcodes.ALOAD -> loadLocal(((VarInsnNode) insn).var);
            case Opcodes.ASTORE -> storeLocal(((VarInsnNode) insn).var);
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> arith(op);
            case Opcodes.IDIV, Opcodes.IREM -> divRem(op);
            case Opcodes.INEG -> {
                String a = popInt();
                String r = nv("v");
                body.append("  ").append(r).append(" = sub i32 0, ").append(a).append("\n");
                pushInt(r);
            }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> ifSingle(op, (JumpInsnNode) insn);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> ifCompare(op, (JumpInsnNode) insn);
            case Opcodes.GOTO -> {
                LabelNode target = ((JumpInsnNode) insn).label;
                body.append("  br label %").append(label(target)).append("\n");
                terminated = true;
            }
            case Opcodes.POP -> pop();
            case Opcodes.DUP -> {
                String v = pop();
                push(v);
                push(v);
            }
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (!Natives.isSystemOut(f.owner, f.name)) {
                    fail("unsupported getstatic " + f.owner + "." + f.name);
                }
                pushIntLit(0);
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> invoke((MethodInsnNode) insn);
            case Opcodes.LABEL -> body.append(label((LabelNode) insn)).append(":\n");
            default -> fail("unsupported opcode " + op + " in " + owner.name() + "." + mn.name);
        }
    }

    private void invoke(MethodInsnNode m) {
        Natives.Sig sig = Natives.lookup(m.owner, m.name, m.desc);
        if (sig != null) {
            String[] args = new String[sig.argCount];
            for (int i = sig.argCount - 1; i >= 0; i--) {
                args[i] = pop();
            }
            if ("print_str".equals(sig.cName) || "print_str_raw".equals(sig.cName)) {
                if ("print_str".equals(sig.cName)) {
                    declares.add("declare i32 @puts(ptr)");
                    body.append("  ").append(nv("r")).append(" = call i32 @puts(ptr ").append(toPtr(args[0])).append(")\n");
                } else {
                    String fmt = pool.internFormat("%s");
                    declares.add("declare i32 @printf(ptr, ...)");
                    body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", ptr ").append(toPtr(args[0])).append(")\n");
                }
            } else if ("print_int".equals(sig.cName) || "print_int_raw".equals(sig.cName)) {
                String fmt = pool.internFormat(if ("print_int".equals(sig.cName)) "%d\\0A" else "%d");
                declares.add("declare i32 @printf(ptr, ...)");
                body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(", i32 ").append(toInt(args[0])).append(")\n");
            } else if ("print_nl".equals(sig.cName)) {
                String fmt = pool.internFormat("\\0A");
                declares.add("declare i32 @printf(ptr, ...)");
                body.append("  ").append(nv("r")).append(" = call i32 (ptr, ...) @printf(ptr ").append(fmt).append(")\n");
            } else if ("math_abs".equals(sig.cName)) {
                String x = toInt(args[0]);
                String neg = nv("v");
                body.append("  ").append(neg).append(" = sub i32 0, ").append(x).append("\n");
                String c = nv("c");
                body.append("  ").append(c).append(" = icmp slt i32 ").append(x).append(", 0\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = select i1 ").append(c).append(", i32 ").append(neg).append(", i32 ").append(x).append("\n");
                pushInt(r);
            } else if ("math_max".equals(sig.cName) || "math_min".equals(sig.cName)) {
                String a = toInt(args[0]);
                String b = toInt(args[1]);
                String c = nv("c");
                body.append("  ").append(c).append(" = icmp ").append("math_max".equals(sig.cName) ? "sgt" : "slt").append(" i32 ").append(a).append(", ").append(b).append("\n");
                String r = nv("v");
                body.append("  ").append(r).append(" = select i1 ").append(c).append(", i32 ").append(a).append(", i32 ").append(b).append("\n");
                pushInt(r);
            } else {
                fail("unregistered native " + sig.cName);
            }
            return;
        }
        String target = ClassParser.mangle(m.owner, m.name, m.desc);
        List<String> kinds = TypeUtil.argKinds(m.desc);
        String[] raw = new String[kinds.size()];
        for (int i = kinds.size() - 1; i >= 0; i--) {
            raw[i] = pop();
        }
        boolean isStaticCall = m.getOpcode() == Opcodes.INVOKESTATIC;
        if (!isStaticCall) {
            pop();
        }
        StringBuilder callArgs = new StringBuilder();
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) {
                callArgs.append(", ");
            }
            if ("I".equals(kinds.get(i))) {
                callArgs.append("i32 ").append(toInt(raw[i]));
            } else {
                callArgs.append("i64 ").append(raw[i]);
            }
        }
        String r = nv("r");
        body.append("  ").append(r).append(" = call i32 @").append(target).append("(").append(callArgs).append(")\n");
        if ("V".equals(TypeUtil.returnKind(m.desc))) {
            pushIntLit(0);
        } else {
            push(r);
        }
    }

    private void arith(int op) {
        String b = popInt();
        String a = popInt();
        String opName = switch (op) {
            case Opcodes.IADD -> "add";
            case Opcodes.ISUB -> "sub";
            case Opcodes.IMUL -> "mul";
            case Opcodes.IAND -> "and";
            case Opcodes.IOR -> "or";
            default -> "xor";
        };
        String r = nv("v");
        body.append("  ").append(r).append(" = ").append(opName).append(" i32 ").append(a).append(", ").append(b).append("\n");
        pushInt(r);
    }

    private void divRem(int op) {
        String b = popInt();
        String a = popInt();
        String kind = op == Opcodes.IDIV ? "sdiv" : "srem";
        String safe = nv("v");
        String zc = nv("c");
        body.append("  ").append(zc).append(" = icmp eq i32 ").append(b).append(", 0\n");
        String bb = nv("b");
        body.append("  ").append(bb).append(" = select i1 ").append(zc).append(", i32 1, i32 ").append(b).append("\n");
        body.append("  ").append(safe).append(" = ").append(kind).append(" i32 ").append(a).append(", ").append(bb).append("\n");
        pushInt(safe);
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
        String t = label(j.label);
        String f = nv("fall");
        body.append("  br i1 ").append(c).append(", label %").append(t).append(", label %").append(f).append("\n");
        body.append(f).append(":\n");
        terminated = false;
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
        String t = label(j.label);
        String f = nv("fall");
        body.append("  br i1 ").append(c).append(", label %").append(t).append(", label %").append(f).append("\n");
        body.append(f).append(":\n");
    }

    private void pushIntLit(int v) {
        String e = nv("e");
        body.append("  ").append(e).append(" = sext i32 ").append(v).append(" to i64\n");
        push(e);
    }

    private void pushInt(String v32) {
        String e = nv("e");
        body.append("  ").append(e).append(" = sext i32 ").append(v32).append(" to i64\n");
        push(e);
    }

    private void pushStringLit(String s) {
        String g = pool.intern(s);
        push("ptrtoint (ptr " + g + " to i64)");
    }

    private void pushIntFromLocal(int idx) {
        String v = loadLocal(idx);
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v).append(" to i32\n");
        pushInt(t);
    }

    private String loadLocal(int idx) {
        String p = nv("lp");
        body.append("  ").append(p).append(" = getelementptr [").append(Math.max(mn.maxLocals, 1)).append(" x i64], ptr %locals, i64 0, i32 ").append(idx).append("\n");
        String v = nv("lv");
        body.append("  ").append(v).append(" = load i64, ptr ").append(p).append("\n");
        return v;
    }

    private void storeLocal(int idx) {
        String v = pop();
        String p = nv("lp");
        body.append("  ").append(p).append(" = getelementptr [").append(Math.max(mn.maxLocals, 1)).append(" x i64], ptr %locals, i64 0, i32 ").append(idx).append("\n");
        body.append("  store i64 ").append(v).append(", ptr ").append(p).append("\n");
    }

    private void push(String v64) {
        String d = nv("d");
        body.append("  ").append(d).append(" = load i32, ptr %sp\n");
        String slot = nv("sl");
        body.append("  ").append(slot).append(" = getelementptr [").append(STACK_SLOTS).append(" x i64], ptr %stack, i64 0, i32 ").append(d).append("\n");
        body.append("  store i64 ").append(v64).append(", ptr ").append(slot).append("\n");
        String n = nv("n");
        body.append("  ").append(n).append(" = add i32 ").append(d).append(", 1\n");
        body.append("  store i32 ").append(n).append(", ptr %sp\n");
    }

    private String pop() {
        String d = nv("d");
        body.append("  ").append(d).append(" = load i32, ptr %sp\n");
        String d0 = nv("d");
        body.append("  ").append(d0).append(" = sub i32 ").append(d).append(", 1\n");
        body.append("  store i32 ").append(d0).append(", ptr %sp\n");
        String slot = nv("sl");
        body.append("  ").append(slot).append(" = getelementptr [").append(STACK_SLOTS).append(" x i64], ptr %stack, i64 0, i32 ").append(d0).append("\n");
        String v = nv("v");
        body.append("  ").append(v).append(" = load i64, ptr ").append(slot).append("\n");
        return v;
    }

    private String popInt() {
        String v = pop();
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v).append(" to i32\n");
        return t;
    }

    private String toInt(String v64) {
        String t = nv("v");
        body.append("  ").append(t).append(" = trunc i64 ").append(v64).append(" to i32\n");
        return t;
    }

    private String toPtr(String v64) {
        String t = nv("v");
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