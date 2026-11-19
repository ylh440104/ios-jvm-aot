package dev.cmc.aot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class ClosureSupport {

    record Target(String owner, String name, String desc) {
    }

    static final List<Target> TARGETS = new ArrayList<>();

    private ClosureSupport() {
    }

    static String symbolOf(String owner, String name, String desc) {
        for (int i = 0; i < TARGETS.size(); i++) {
            Target t = TARGETS.get(i);
            if (t.owner().equals(owner) && t.name().equals(name) && t.desc().equals(desc)) {
                return "aot_cbk_" + i;
            }
        }
        TARGETS.add(new Target(owner, name, desc));
        return "aot_cbk_" + (TARGETS.size() - 1);
    }

    static boolean isEmpty() {
        return TARGETS.isEmpty();
    }

    static int retCode(String desc) {
        return switch (TypeInfo.returnKind(desc)) {
            case "V" -> 0;
            case "I" -> 1;
            case "F" -> 3;
            case "D" -> 4;
            default -> 2;
        };
    }

    static List<Integer> argCodes(String desc) {
        List<Integer> out = new ArrayList<>();
        for (String k : TypeInfo.argKinds(desc)) {
            out.add(switch (k) {
                case "I" -> 1;
                case "F" -> 3;
                case "D" -> 4;
                default -> 2;
            });
        }
        return out;
    }

    static String render() {
        StringBuilder sb = new StringBuilder();
        Set<String> done = new LinkedHashSet<>();
        for (int i = 0; i < TARGETS.size(); i++) {
            Target t = TARGETS.get(i);
            String mangled = ClassParser.mangle(t.owner(), t.name(), t.desc());
            if (!done.add(mangled)) {
                continue;
            }
            List<String> kinds = TypeInfo.argKinds(t.desc());
            String retKind = TypeInfo.returnKind(t.desc());
            String retLlvm = switch (retKind) {
                case "V", "I", "F" -> "i32";
                default -> "i64";
            };
            sb.append("define i64 @aot_cbk_").append(i).append("(i64 %slots) {\n");
            sb.append("entry:\n");
            sb.append("  %ap = inttoptr i64 %slots to ptr\n");
            StringBuilder callArgs = new StringBuilder();
            for (int k = 0; k < kinds.size(); k++) {
                String kd = kinds.get(k);
                sb.append("  %e").append(k).append(" = getelementptr i64, ptr %ap, i32 ").append(k).append("\n");
                sb.append("  %r").append(k).append(" = load i64, ptr %e").append(k).append("\n");
                if (k > 0) {
                    callArgs.append(", ");
                }
                if ("I".equals(kd) || "F".equals(kd)) {
                    sb.append("  %t").append(k).append(" = trunc i64 %r").append(k).append(" to i32\n");
                    callArgs.append("i32 %t").append(k);
                } else {
                    callArgs.append(TypeInfo.llvm(kd)).append(" %r").append(k);
                }
            }
            sb.append("  %res = call ").append(retLlvm).append(" @").append(mangled).append("(")
                .append(callArgs).append(")\n");
            switch (retKind) {
                case "V" -> sb.append("  ret i64 0\n}\n");
                case "I", "F" -> sb.append("  %rx = sext i32 %res to i64\n  ret i64 %rx\n}\n");
                default -> sb.append("  ret i64 %res\n}\n");
            }
        }
        return sb.toString();
    }

    static String declareFor(List<ClassNode> classes) {
        StringBuilder sb = new StringBuilder();
        Set<String> done = new LinkedHashSet<>();
        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                for (Target t : TARGETS) {
                    if (!t.owner().equals(cn.name) || !t.name().equals(mn.name) || !t.desc().equals(mn.desc)) {
                        continue;
                    }
                    String mangled = ClassParser.mangle(cn.name, mn.name, mn.desc);
                    if (!done.add(mangled)) {
                        continue;
                    }
                    sb.append("declare ").append(ClosureSupport.retLlvm(mn.desc)).append(" @")
                        .append(mangled).append("(");
                    List<String> ks = TypeInfo.argKinds(mn.desc);
                    for (int i = 0; i < ks.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append("I".equals(ks.get(i)) || "F".equals(ks.get(i)) ? "i32" : TypeInfo.llvm(ks.get(i)));
                    }
                    sb.append(")\n");
                }
            }
        }
        return sb.toString();
    }

    static String retLlvm(String desc) {
        return switch (TypeInfo.returnKind(desc)) {
            case "V", "I", "F" -> "i32";
            default -> "i64";
        };
    }
}