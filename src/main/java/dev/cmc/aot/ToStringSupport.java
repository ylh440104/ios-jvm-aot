package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

final class ToStringSupport {

    private ToStringSupport() {
    }

    static String render(List<ClassInfo> classes, Layout layout) {
        List<String> impls = new ArrayList<>();
        StringBuilder bodies = new StringBuilder();
        for (ClassInfo c : classes) {
            MethodNode mn = c.findMethod("toString", "()Ljava/lang/String;");
            if (mn == null || ClassParser.isStatic(mn)) {
                continue;
            }
            String target = ClassParser.mangle(c.name(), "toString", "()Ljava/lang/String;");
            String wrapper = "aot_tstr_" + ClassParser.mangle(c.name(), "s", "()V");
            impls.add(c.name());
            bodies.append("define i64 @").append(wrapper).append("(i64 %self) {\n");
            bodies.append("entry:\n");
            bodies.append("  %r = call i64 @").append(target).append("(i64 %self)\n");
            bodies.append("  ret i64 %r\n");
            bodies.append("}\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bodies);
        sb.append("define i64 @aot_tostring_of(i32 %tid, i64 %self) {\nentry:\n");
        if (impls.isEmpty()) {
            sb.append("  ret i64 0\n}\n");
            return sb.toString();
        }
        for (int i = 0; i < impls.size(); i++) {
            String cls = impls.get(i);
            String wrapper = "aot_tstr_" + ClassParser.mangle(cls, "s", "()V");
            sb.append("  %c").append(i).append(" = icmp eq i32 %tid, ").append(layout.typeId(cls)).append("\n");
            sb.append("  br i1 %c").append(i).append(", label %hit").append(i).append(", label %next").append(i).append("\n");
            sb.append("hit").append(i).append(":\n");
            sb.append("  %r").append(i).append(" = call i64 @").append(wrapper).append("(i64 %self)\n");
            sb.append("  ret i64 %r").append(i).append("\n");
            sb.append("next").append(i).append(":\n");
        }
        sb.append("  ret i64 0\n}\n");
        return sb.toString();
    }
}