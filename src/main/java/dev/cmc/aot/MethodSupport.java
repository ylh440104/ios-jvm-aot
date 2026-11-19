package dev.cmc.aot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MethodSupport {

    private MethodSupport() {
    }

    static final class SlotInfo {
        final String key;
        final String name;
        final String desc;
        final int slot;

        SlotInfo(String key, String name, String desc, int slot) {
            this.key = key;
            this.name = name;
            this.desc = desc;
            this.slot = slot;
        }
    }

    static List<SlotInfo> slots(Layout layout) {
        List<SlotInfo> out = new ArrayList<>();
        Map<String, Integer> map = layout.slotMap();
        for (Map.Entry<String, Integer> e : new LinkedHashMap<>(map).entrySet()) {
            String key = e.getKey();
            int paren = key.indexOf('(');
            if (paren < 0) {
                continue;
            }
            out.add(new SlotInfo(key, key.substring(0, paren), key.substring(paren), e.getValue()));
        }
        return out;
    }

    static String renderTrampolines(Layout layout) {
        List<SlotInfo> list = slots(layout);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            SlotInfo s = list.get(i);
            List<String> kinds = TypeInfo.argKinds(s.desc);
            String retKind = TypeInfo.returnKind(s.desc);
            String retLlvm = switch (retKind) {
                case "V", "I", "F" -> "i32";
                default -> "i64";
            };
            sb.append("define i64 @aot_mslot_").append(i).append("(i64 %self, i64 %args) {\n");
            sb.append("entry:\n");
            sb.append("  %ap = inttoptr i64 %args to ptr\n");
            sb.append("  %sp = inttoptr i64 %self to ptr\n");
            sb.append("  %vt = load ptr, ptr %sp\n");
            sb.append("  %fp = getelementptr ptr, ptr %vt, i32 ").append(s.slot).append("\n");
            sb.append("  %fn = load ptr, ptr %fp\n");
            StringBuilder callArgs = new StringBuilder("i64 %self");
            for (int k = 0; k < kinds.size(); k++) {
                String kd = kinds.get(k);
                String llvmType = "F".equals(kd) ? "i32" : TypeInfo.llvm(kd);
                sb.append("  %e").append(k).append(" = getelementptr i64, ptr %ap, i32 ").append(k).append("\n");
                sb.append("  %r").append(k).append(" = load i64, ptr %e").append(k).append("\n");
                if ("I".equals(kd)) {
                    sb.append("  %t").append(k).append(" = trunc i64 %r").append(k).append(" to i32\n");
                    callArgs.append(", i32 %t").append(k);
                } else if ("F".equals(kd)) {
                    sb.append("  %t").append(k).append(" = trunc i64 %r").append(k).append(" to i32\n");
                    callArgs.append(", i32 %t").append(k);
                } else {
                    callArgs.append(", ").append(llvmType).append(" %r").append(k);
                }
            }
            sb.append("  %res = call ").append(retLlvm).append(" %fn(").append(callArgs).append(")\n");
            if ("V".equals(retKind)) {
                sb.append("  ret i64 0\n}\n");
            } else if ("I".equals(retKind)) {
                sb.append("  %rx = sext i32 %res to i64\n  ret i64 %rx\n}\n");
            } else if ("F".equals(retKind)) {
                sb.append("  %rx = sext i32 %res to i64\n  ret i64 %rx\n}\n");
            } else {
                sb.append("  ret i64 %res\n}\n");
            }
        }
        return sb.toString();
    }

    static String renderDispatch(Layout layout) {
        List<SlotInfo> list = slots(layout);
        StringBuilder sb = new StringBuilder();
        sb.append("define i64 @aot_mslot_dispatch(i32 %slot, i64 %self, i64 %args) {\nentry:\n");
        for (int i = 0; i < list.size(); i++) {
            SlotInfo s = list.get(i);
            sb.append("  %c").append(i).append(" = icmp eq i32 %slot, ").append(s.slot).append("\n");
            sb.append("  br i1 %c").append(i).append(", label %hit").append(i).append(", label %nx").append(i).append("\n");
            sb.append("hit").append(i).append(":\n");
            sb.append("  %v").append(i).append(" = call i64 @aot_mslot_").append(i).append("(i64 %self, i64 %args)\n");
            sb.append("  ret i64 %v").append(i).append("\n");
            sb.append("nx").append(i).append(":\n");
        }
        sb.append("  ret i64 0\n}\n");
        return sb.toString();
    }

    static List<String[]> methodRows(Layout layout) {
        List<String[]> rows = new ArrayList<>();
        for (ClassInfo c : layout.classInfos()) {
            for (org.objectweb.asm.tree.MethodNode mn : c.methods()) {
                if (ClassParser.isStatic(mn) || ClassParser.isPrivate(mn) || ClassParser.isCtor(mn.name)) {
                    continue;
                }
                Integer slot = layout.slotMap().get(mn.name + mn.desc);
                if (slot == null) {
                    continue;
                }
                rows.add(new String[] { c.name(), mn.name, String.valueOf(slot),
                    String.valueOf(Layout.retCode(mn.desc)) });
            }
        }
        return rows;
    }

    static List<String[]> ctorRows(Layout layout) {
        List<String[]> rows = new ArrayList<>();
        for (ClassInfo c : layout.classInfos()) {
            for (org.objectweb.asm.tree.MethodNode mn : c.methods()) {
                if (!ClassParser.isCtor(mn.name) || ClassParser.isPrivate(mn)) {
                    continue;
                }
                if (!layout.hasClass(c.name())) {
                    continue;
                }
                rows.add(new String[] { c.name(), mn.desc, ClassParser.mangle(c.name(), mn.name, mn.desc) });
            }
        }
        return rows;
    }

    static String renderCtorMeta(Layout layout) {
        List<String[]> rows = ctorRows(layout);
        StringBuilder sb = new StringBuilder();
        int slots = Math.max(rows.size() * 4, 4);
        sb.append("@.ctor_meta = global [").append(slots).append(" x i64] zeroinitializer\n");
        int i = 0;
        for (String[] row : rows) {
            sb.append("@.cdname_").append(i).append(" = private constant [").append(row[1].length())
                .append(" x i8] c\"").append(row[1]).append("\"\n");
            i++;
        }
        return sb.toString();
    }

    static String renderCtorWrappers(Layout layout) {
        List<String[]> rows = ctorRows(layout);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            List<String> kinds = TypeInfo.argKinds(row[1]);
            sb.append("define i64 @aot_ctor_").append(i).append("(i64 %args) {\nentry:\n");
            sb.append("  %ap = inttoptr i64 %args to ptr\n");
            int size = layout.objectSize(row[0]);
            sb.append("  %np = call ptr @gc_calloc(i64 ").append(size).append(", i64 1)\n");
            sb.append("  store ptr ").append(layout.vtableLabel(row[0])).append(", ptr %np\n");
            sb.append("  %tip = getelementptr i8, ptr %np, i32 8\n");
            sb.append("  store i32 ").append(layout.typeId(row[0])).append(", ptr %tip\n");
            sb.append("  %selfv = ptrtoint ptr %np to i64\n");
            StringBuilder callArgs = new StringBuilder("i64 %selfv");
            for (int k = 0; k < kinds.size(); k++) {
                String kd = kinds.get(k);
                sb.append("  %e").append(k).append(" = getelementptr i64, ptr %ap, i32 ").append(k).append("\n");
                sb.append("  %r").append(k).append(" = load i64, ptr %e").append(k).append("\n");
                if ("I".equals(kd) || "F".equals(kd)) {
                    sb.append("  %t").append(k).append(" = trunc i64 %r").append(k).append(" to i32\n");
                    callArgs.append(", i32 %t").append(k);
                } else {
                    callArgs.append(", ").append(TypeInfo.llvm(kd)).append(" %r").append(k);
                }
            }
            sb.append("  call i32 @").append(row[2]).append("(").append(callArgs).append(")\n");
            sb.append("  ret i64 %selfv\n}\n");
        }
        return sb.toString();
    }

    static String renderCtorMetaInit(Layout layout) {
        List<String[]> rows = ctorRows(layout);
        StringBuilder sb = new StringBuilder();
        int slabs = Math.max(rows.size() * 4, 4);
        int i = 0;
        for (String[] row : rows) {
            sb.append("  %cd").append(i).append(" = call i64 @aot_str_from_utf8(i64 ")
                .append("ptrtoint (ptr @.cdname_").append(i).append(" to i64), i32 ")
                .append(row[1].length()).append(")\n");
            sb.append("  %c0_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.ctor_meta, i64 0, i32 ").append(i * 4).append("\n");
            sb.append("  store i64 ").append(layout.typeId(row[0])).append(", ptr %c0_").append(i).append("\n");
            sb.append("  %c1_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.ctor_meta, i64 0, i32 ").append(i * 4 + 1).append("\n");
            sb.append("  store i64 %cd").append(i).append(", ptr %c1_").append(i).append("\n");
            sb.append("  %c2_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.ctor_meta, i64 0, i32 ").append(i * 4 + 2).append("\n");
            sb.append("  store i64 ptrtoint (ptr @aot_ctor_").append(i).append(" to i64), ptr %c2_").append(i).append("\n");
            sb.append("  %c3_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.ctor_meta, i64 0, i32 ").append(i * 4 + 3).append("\n");
            sb.append("  store i64 ").append(kindsCount(row[1])).append(", ptr %c3_").append(i).append("\n");
            i++;
        }
        sb.append("  call void @aot_set_ctor_meta_table(ptr @.ctor_meta, i32 ").append(rows.size()).append(")\n");
        return sb.toString();
    }

    private static int kindsCount(String desc) {
        return TypeInfo.argKinds(desc).size();
    }

    static String renderMethodMeta(Layout layout) {
        List<String[]> rows = methodRows(layout);
        StringBuilder sb = new StringBuilder();
        int slots = Math.max(rows.size() * 4, 4);
        sb.append("@.method_meta = global [").append(slots).append(" x i64] zeroinitializer\n");
        int i = 0;
        for (String[] row : rows) {
            sb.append("@.mname_").append(i).append(" = private constant [").append(row[1].length())
                .append(" x i8] c\"").append(row[1]).append("\"\n");
            i++;
        }
        return sb.toString();
    }

    static String renderMethodMetaInit(Layout layout) {
        List<String[]> rows = methodRows(layout);
        StringBuilder sb = new StringBuilder();
        int slabs = Math.max(rows.size() * 4, 4);
        int i = 0;
        for (String[] row : rows) {
            sb.append("  %mn").append(i).append(" = call i64 @aot_str_from_utf8(i64 ")
                .append("ptrtoint (ptr @.mname_").append(i).append(" to i64), i32 ")
                .append(row[1].length()).append(")\n");
            sb.append("  %m0_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.method_meta, i64 0, i32 ").append(i * 4).append("\n");
            sb.append("  store i64 ").append(layout.typeId(row[0])).append(", ptr %m0_").append(i).append("\n");
            sb.append("  %m1_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.method_meta, i64 0, i32 ").append(i * 4 + 1).append("\n");
            sb.append("  store i64 ").append(row[2]).append(", ptr %m1_").append(i).append("\n");
            sb.append("  %m2_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.method_meta, i64 0, i32 ").append(i * 4 + 2).append("\n");
            sb.append("  store i64 %mn").append(i).append(", ptr %m2_").append(i).append("\n");
            sb.append("  %m3_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.method_meta, i64 0, i32 ").append(i * 4 + 3).append("\n");
            sb.append("  store i64 ").append(row[3]).append(", ptr %m3_").append(i).append("\n");
            i++;
        }
        sb.append("  call void @aot_set_method_meta_table(ptr @.method_meta, i32 ").append(rows.size()).append(")\n");
        return sb.toString();
    }
}