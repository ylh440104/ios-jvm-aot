package dev.cmc.aot;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.ArrayList;
import java.util.List;

public final class TypeInfo {

    private TypeInfo() {
    }

    public static List<String> argKinds(String desc) {
        List<String> out = new ArrayList<>();
        for (Type t : Type.getArgumentTypes(desc)) {
            out.add(kindOf(t));
        }
        return out;
    }

    public static String returnKind(String desc) {
        Type t = Type.getReturnType(desc);
        if (t.getSort() == Type.VOID) {
            return "V";
        }
        return kindOf(t);
    }

    public static String kindOf(Type t) {
        return switch (t.getSort()) {
            case Type.LONG -> "J";
            case Type.FLOAT -> "F";
            case Type.DOUBLE -> "D";
            case Type.ARRAY, Type.OBJECT -> "L";
            case Type.VOID -> "V";
            default -> "I";
        };
    }

    public static String llvm(String kind) {
        return switch (kind) {
            case "J", "D", "L" -> "i64";
            case "F" -> "float";
            default -> "i32";
        };
    }

    public static boolean isWide(String kind) {
        return "J".equals(kind) || "D".equals(kind);
    }

    public static Frame<BasicValue>[] analyze(ClassInfo owner, MethodNode mn) {
        Analyzer<BasicValue> analyzer = new Analyzer<>(new SimpleVerifier());
        try {
            return analyzer.analyze(owner.name(), mn);
        } catch (AnalyzerException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}