package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

final class ThreadSupport {

    private ThreadSupport() {
    }

    static String render(List<ClassInfo> classes, Layout layout) {
        List<String> runners = new ArrayList<>();
        StringBuilder bodies = new StringBuilder();
        for (ClassInfo c : classes) {
            MethodNode mn = c.findMethod("run", "()V");
            if (mn == null || ClassParser.isStatic(mn)) {
                continue;
            }
            String target = ClassParser.mangle(c.name(), "run", "()V");
            String wrapper = "aot_tbody_" + ClassParser.mangle(c.name(), "r", "()V");
            runners.add(c.name());
            bodies.append("define void @").append(wrapper).append("(i64 %self) {\n");
            bodies.append("entry:\n");
            bodies.append("  %r = call i32 @").append(target).append("(i64 %self)\n");
            bodies.append("  ret void\n");
            bodies.append("}\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bodies);
        sb.append("define void @aot_thread_body(i64 %fn, i64 %self) {\nentry:\n");
        sb.append("  %fp = inttoptr i64 %fn to ptr\n");
        sb.append("  call void %fp(i64 %self)\n");
        sb.append("  ret void\n}\n");
        sb.append("define i64 @aot_tbody_lookup(i32 %tid) {\nentry:\n");
        if (runners.isEmpty()) {
            sb.append("  ret i64 0\n}\n");
            return sb.toString();
        }
        for (int i = 0; i < runners.size(); i++) {
            String cls = runners.get(i);
            String wrapper = "aot_tbody_" + ClassParser.mangle(cls, "r", "()V");
            sb.append("  %c").append(i).append(" = icmp eq i32 %tid, ").append(layout.typeId(cls)).append("\n");
            sb.append("  br i1 %c").append(i).append(", label %hit").append(i).append(", label %next").append(i).append("\n");
            sb.append("hit").append(i).append(":\n");
            sb.append("  %p").append(i).append(" = ptrtoint ptr @").append(wrapper).append(" to i64\n");
            sb.append("  ret i64 %p").append(i).append("\n");
            sb.append("next").append(i).append(":\n");
        }
        sb.append("  ret i64 0\n}\n");
        return sb.toString();
    }
}