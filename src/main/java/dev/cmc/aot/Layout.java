package dev.cmc.aot;

import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Layout {

    public static final int HEADER_SIZE = 16;

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, Integer> fieldOffsets = new HashMap<>();
    private final Map<String, Integer> objectSizes = new HashMap<>();
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final List<String> slotKeys = new ArrayList<>();
    private final Map<String, Integer> vtableIndex = new HashMap<>();
    private final Map<String, String> staticFields = new LinkedHashMap<>();
    private final Map<String, Integer> typeIds = new LinkedHashMap<>();

    public int typeId(String cls) {
        Integer id = typeIds.get(cls);
        if (id != null) {
            return id;
        }
        int next = typeIds.size() + 1;
        typeIds.put(cls, next);
        return next;
    }

    public boolean hasTypeId(String cls) {
        return typeIds.containsKey(cls);
    }

    public Map<String, Integer> typeIdMap() {
        return typeIds;
    }

    public void registerInterface(String name, String superName, List<MethodNode> methods) {
        if (name == null || classes.containsKey(name)) {
            return;
        }
        String sup = superName == null ? "java/lang/Object" : superName;
        classes.put(name, new ClassInfo(name, sup, new ArrayList<>(), org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT, methods, new ArrayList<>()));
        typeId(name);
        MethodNode ctor = new MethodNode();
        ctor.name = "<init>";
        ctor.desc = "()V";
        ctor.access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
        ctor.instructions = new org.objectweb.asm.tree.InsnList();
        ctor.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
        classes.get(name).methods().add(ctor);
        assignSlots();
        buildVtables();
        assignFieldOffsets();
    }

    public List<String> ancestry(String cls) {
        return fieldChain(cls);
    }

    public String renderTypeTable() {
        return "";
    }

    public String renderTypeInit() {
        return "";
    }

    private int superIdOf(String cls) {
        ClassInfo c = classes.get(cls);
        if (c == null || c.superName() == null) {
            return 0;
        }
        Integer sid = typeIds.get(c.superName());
        return sid == null ? 0 : sid;
    }

    private final Map<String, String> typeTables = new LinkedHashMap<>();
    private final List<String> tableTargets = new ArrayList<>();

    public String assignTable(String target) {
        String existing = typeTables.get(target);
        if (existing != null) {
            return existing;
        }
        typeId(target);
        String label = "@.typetbl_" + ClassParser.mangle(target, "t", "()V");
        typeTables.put(target, label);
        tableTargets.add(target);
        return label;
    }

    public String renderTypeTables() {
        StringBuilder out = new StringBuilder();
        int slots = Math.max(typeIds.size() + 1, 2);
        for (String target : tableTargets) {
            StringBuilder vals = new StringBuilder();
            java.util.Set<Integer> marked = new java.util.HashSet<>();
            for (Map.Entry<String, Integer> e : typeIds.entrySet()) {
                if (ancestryOf(e.getKey()).contains(target)) {
                    marked.add(e.getValue());
                }
            }
            for (int i = 0; i < slots; i++) {
                if (i > 0) {
                    vals.append(", ");
                }
                vals.append("i32 ").append(marked.contains(i) ? "1" : "0");
            }
            out.append(typeTables.get(target)).append(" = private unnamed_addr constant [").append(slots)
                .append(" x i32] [").append(vals).append("]\n");
        }
        return out.toString();
    }
    private final Map<String, java.util.Set<String>> ancestryCache = new java.util.HashMap<>();

    private java.util.Set<String> ancestryOf(String cls) {
        java.util.Set<String> cached = ancestryCache.get(cls);
        if (cached != null) {
            return cached;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collectAncestry(cls, out);
        ancestryCache.put(cls, out);
        return out;
    }

    private void collectAncestry(String cls, java.util.Set<String> out) {
        if (cls == null || !out.add(cls)) {
            return;
        }
        ClassInfo c = classes.get(cls);
        if (c == null) {
            return;
        }
        collectAncestry(c.superName(), out);
        for (String itf : c.interfaces()) {
            collectAncestry(itf, out);
        }
    }

    public String renderAssignTable() {
        int n = Math.max(maxTypeId(), 2);
        StringBuilder out = new StringBuilder();
        out.append("@.assign_tbl = global [").append((long)n * n).append(" x i8] zeroinitializer\n");
        return out.toString();
    }

    public String renderAssignInit() {
        int n = Math.max(maxTypeId(), 2);
        StringBuilder out = new StringBuilder();
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, Integer> sub : new java.util.LinkedHashMap<>(typeIds).entrySet()) {
            java.util.Set<String> anc = ancestryOf(sub.getKey());
            for (String sup : anc) {
                Integer sid = typeIds.get(sup);
                if (sid == null) {
                    continue;
                }
                pairs.add(new String[] { String.valueOf(sub.getValue()), String.valueOf(sid) });
            }
        }
        int i = 0;
        for (String[] p : pairs) {
            out.append("  %ag").append(i).append(" = getelementptr [").append((long)n * n)
                .append(" x i8], ptr @.assign_tbl, i64 0, i64 ")
                .append((long)Integer.parseInt(p[0]) * n + Integer.parseInt(p[1])).append("\n");
            out.append("  store i8 1, ptr %ag").append(i).append("\n");
            i++;
        }
        out.append("  call void @aot_set_assign_table(ptr @.assign_tbl, i32 ").append(n).append(")\n");
        return out.toString();
    }

    public String renderSuperTable() {
        StringBuilder out = new StringBuilder();
        int slots = Math.max(maxTypeId(), 2);
        out.append("@.super_of = global [").append(slots).append(" x i32] zeroinitializer\n");
        return out.toString();
    }

    public String renderSuperInit() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : new java.util.LinkedHashMap<>(typeIds).entrySet()) {
            String name = e.getKey();
            int tid = e.getValue();
            String sup = "0";
            ClassInfo c = classes.get(name);
            if (c != null && c.superName() != null && !c.isInterface()) {
                Integer sid = typeIds.get(c.superName());
                if (sid != null) {
                    sup = String.valueOf(sid);
                }
            }
            out.append("  %sp").append(tid).append(" = getelementptr [").append(Math.max(maxTypeId(), 2))
                .append(" x i32], ptr @.super_of, i64 0, i32 ").append(tid).append("\n");
            out.append("  store i32 ").append(sup).append(", ptr %sp").append(tid).append("\n");
        }
        out.append("  call void @aot_set_super_table(ptr @.super_of, i32 ").append(Math.max(maxTypeId(), 2)).append(")\n");
        return out.toString();
    }

    public String renderIfaceTable() {
        StringBuilder out = new StringBuilder();
        int slots = Math.max(maxTypeId(), 2);
        out.append("@.iface_of = global [").append(slots).append(" x i32] zeroinitializer\n");
        return out.toString();
    }

    public String renderIfaceInit() {
        StringBuilder out = new StringBuilder();
        int slots = Math.max(maxTypeId(), 2);
        for (Map.Entry<String, Integer> e : new java.util.LinkedHashMap<>(typeIds).entrySet()) {
            ClassInfo c = classes.get(e.getKey());
            int val = c != null && c.isInterface() ? 1 : 0;
            int tid = e.getValue();
            out.append("  %ifp").append(tid).append(" = getelementptr [").append(slots)
                .append(" x i32], ptr @.iface_of, i64 0, i32 ").append(tid).append("\n");
            out.append("  store i32 ").append(val).append(", ptr %ifp").append(tid).append("\n");
        }
        out.append("  call void @aot_set_iface_table(ptr @.iface_of, i32 ").append(slots).append(")\n");
        return out.toString();
    }

    public int typeTableSlots() {
        return Math.max(typeIds.size() + 1, 2);
    }

    private Layout() {
    }

    public static Layout build(List<ClassInfo> all) {
        Layout l = new Layout();
        for (ClassInfo c : all) {
            l.classes.put(c.name(), c);
        }
        l.registerBuiltins();
        for (ClassInfo c : all) {
            l.typeId(c.name());
        }
        l.assignSlots();
        l.assignFieldOffsets();
        l.buildVtables();
        return l;
    }

    private void registerBuiltins() {
        addBuiltin("java/lang/Object", null);
        addBuiltin("java/lang/Throwable", "java/lang/Object");
        addBuiltin("java/lang/Exception", "java/lang/Throwable");
        addBuiltin("java/lang/RuntimeException", "java/lang/Exception");
        addBuiltin("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException");
        addBuiltin("java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException");
        addBuiltin("java/lang/NullPointerException", "java/lang/RuntimeException");
        addBuiltin("java/lang/ClassCastException", "java/lang/RuntimeException");
        addBuiltin("java/lang/ArithmeticException", "java/lang/RuntimeException");
        addBuiltin("java/lang/NegativeArraySizeException", "java/lang/RuntimeException");
        addBuiltin("java/lang/IllegalStateException", "java/lang/RuntimeException");
        addBuiltin("java/lang/IllegalArgumentException", "java/lang/RuntimeException");
        addBuiltin("java/lang/UnsupportedOperationException", "java/lang/RuntimeException");
        addBuiltin("java/lang/OutOfMemoryError", "java/lang/Object");
        addBuiltin("java/lang/String", "java/lang/Object");
        addBuiltin("java/lang/CharSequence", "java/lang/Object");
        addBuiltin("java/lang/StringBuilder", "java/lang/Object");
        addBuiltin("java/lang/StringBuffer", "java/lang/Object");
        addBuiltin("java/lang/Integer", "java/lang/Object");
        addBuiltin("java/lang/Long", "java/lang/Object");
        addBuiltin("java/lang/Boolean", "java/lang/Object");
        addBuiltin("java/lang/Double", "java/lang/Object");
        addBuiltin("java/lang/Float", "java/lang/Object");
        addBuiltin("java/lang/Short", "java/lang/Object");
        addBuiltin("java/lang/Byte", "java/lang/Object");
        addBuiltin("java/lang/Character", "java/lang/Object");
        addBuiltin("java/lang/Number", "java/lang/Object");
        addBuiltin("java/lang/Runnable", "java/lang/Object");
        addBuiltin("java/lang/Thread", "java/lang/Object");
        addBuiltin("java/lang/Iterable", "java/lang/Object");
        addBuiltin("java/util/Collection", "java/lang/Object");
        addBuiltin("java/util/List", "java/util/Collection");
        addBuiltin("java/util/Set", "java/util/Collection");
        addBuiltin("java/util/Map", "java/lang/Object");
        addBuiltin("java/util/ArrayList", "java/lang/Object");
        addBuiltin("java/util/LinkedList", "java/lang/Object");
        addBuiltin("java/util/HashMap", "java/lang/Object");
        addBuiltin("java/util/LinkedHashMap", "java/lang/Object");
        addBuiltin("java/util/Arrays", "java/lang/Object");
        addBuiltin("java/lang/Class", "java/lang/Object");
        addBuiltin("java/lang/reflect/Field", "java/lang/Object");
        addBuiltin("java/lang/reflect/Method", "java/lang/Object");
        addBuiltin("java/lang/reflect/AccessibleObject", "java/lang/Object");
        addBuiltin("java/io/Serializable", "java/lang/Object");
        addBuiltin("java/lang/Cloneable", "java/lang/Object");
    }

    private void addBuiltin(String name, String superName) {
        if (classes.containsKey(name)) {
            return;
        }
        classes.put(name, new ClassInfo(name, superName, new ArrayList<>(), 0, new ArrayList<>(), new ArrayList<>()));
    }

    public String arrayTypeId(String desc) {
        return "[L" + desc + ";";
    }

    private void assignSlots() {
        for (ClassInfo c : classes.values()) {
            for (MethodNode mn : c.methods()) {
                if (ClassParser.isStatic(mn) || ClassParser.isPrivate(mn) || ClassParser.isCtor(mn.name)) {
                    continue;
                }
                registerSlot(mn.name, mn.desc);
            }
        }
        for (ClassInfo c : classes.values()) {
            collectHierarchySlots(c);
        }
    }

    private void registerSlot(String name, String desc) {
        String key = name + desc;
        if (!slots.containsKey(key)) {
            slots.put(key, slotKeys.size());
            slotKeys.add(key);
        }
    }

    private void collectHierarchySlots(ClassInfo c) {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (c.superName() != null) {
            queue.add(c.superName());
        }
        if (c.interfaces() != null) {
            queue.addAll(c.interfaces());
        }
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name == null || !seen.add(name)) {
                continue;
            }
            ClassInfo up = classes.get(name);
            if (up == null) {
                continue;
            }
            for (MethodNode mn : up.methods()) {
                if (ClassParser.isStatic(mn) || ClassParser.isPrivate(mn) || ClassParser.isCtor(mn.name)) {
                    continue;
                }
                registerSlot(mn.name, mn.desc);
            }
            if (up.superName() != null) {
                queue.add(up.superName());
            }
            if (up.interfaces() != null) {
                queue.addAll(up.interfaces());
            }
        }
    }

    private void assignFieldOffsets() {
        for (ClassInfo c : classes.values()) {
            int offset = HEADER_SIZE;
            for (String name : fieldChain(c.name())) {
                ClassInfo owner = classes.get(name);
                if (owner == null) {
                    continue;
                }
                for (FieldInfo f : owner.fields()) {
                    if (f.isStatic()) {
                        continue;
                    }
                    String key = name + "#" + f.name();
                    Integer existing = fieldOffsets.get(key);
                    if (existing == null) {
                        fieldOffsets.put(key, offset);
                        offset += 8;
                    } else {
                        offset = Math.max(offset, existing + 8);
                    }
                }
            }
            objectSizes.put(c.name(), offset);
        }
    }

    private List<String> fieldChain(String cls) {
        List<String> chain = new ArrayList<>();
        String cur = cls;
        while (cur != null && classes.containsKey(cur)) {
            chain.add(0, cur);
            cur = classes.get(cur).superName();
        }
        return chain;
    }

    private void buildVtables() {
        for (ClassInfo c : classes.values()) {
            boolean[] used = new boolean[slotKeys.size()];
            for (String name : fieldChain(c.name())) {
                ClassInfo owner = classes.get(name);
                if (owner == null) {
                    continue;
                }
                for (MethodNode mn : owner.methods()) {
                    if (ClassParser.isStatic(mn) || ClassParser.isPrivate(mn) || ClassParser.isCtor(mn.name)) {
                        continue;
                    }
                    String key = mn.name + mn.desc;
                    Integer idx = slots.get(key);
                    if (idx != null) {
                        used[idx] = true;
                    }
                }
            }
            vtableIndex.put(c.name(), used.length);
        }
    }

    public boolean hasClass(String name) {
        return classes.containsKey(name);
    }

    public boolean knownType(String name) {
        return classes.containsKey(name);
    }

    public ClassInfo get(String name) {
        return classes.get(name);
    }

    public int slotCount() {
        return slotKeys.size();
    }

    public int slotOf(String name, String desc) {
        Integer i = slots.get(name + desc);
        if (i == null) {
            throw new AotException("no vtable slot for " + name + desc + " | total_slots=" + slotKeys.size() + " present_sample=" + (slotKeys.isEmpty() ? "-" : slotKeys.get(0)));
        }
        return i;
    }

    public List<ClassInfo> classInfos() {
        return new ArrayList<>(classes.values());
    }

    public List<String> typeDescTable() {
        int max = maxTypeId();
        String[] arr = new String[max];
        for (Map.Entry<String, Integer> e : new java.util.LinkedHashMap<>(typeIds).entrySet()) {
            int id = e.getValue();
            if (id > 0 && id < max) {
                arr[id] = jvmDesc(e.getKey());
            }
        }
        return java.util.Arrays.asList(arr);
    }

    private static String jvmDesc(String name) {
        if (name.length() == 1) {
            return name;
        }
        if (name.startsWith("[")) {
            return name;
        }
        return "L" + name + ";";
    }

    public String renderTypeDescTable() {
        List<String> descs = typeDescTable();
        StringBuilder sb = new StringBuilder();
        sb.append("@.type_desc = global [").append(descs.size()).append(" x i64] zeroinitializer\n");
        int i = 0;
        for (String d : descs) {
            if (d == null) {
                i++;
                continue;
            }
            String esc = escapeAsmString(d);
            sb.append("@.tdesc_").append(i).append(" = private constant [").append(esc.length())
                .append(" x i8] c\"").append(esc).append("\"\n");
            i++;
        }
        return sb.toString();
    }

    public String renderTypeDescInit() {
        List<String> descs = typeDescTable();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String d : descs) {
            if (d == null) {
                i++;
                continue;
            }
            String esc = escapeAsmString(d);
            sb.append("  %td").append(i).append(" = call i64 @aot_str_from_utf8(i64 ptrtoint (ptr @.tdesc_")
                .append(i).append(" to i64), i32 ").append(esc.length()).append(")\n");
            sb.append("  %tp").append(i).append(" = getelementptr [").append(descs.size())
                .append(" x i64], ptr @.type_desc, i64 0, i32 ").append(i).append("\n");
            sb.append("  store i64 %td").append(i).append(", ptr %tp").append(i).append("\n");
            i++;
        }
        sb.append("  call void @aot_set_type_desc_table(ptr @.type_desc, i32 ").append(descs.size()).append(")\n");
        return sb.toString();
    }

    public int fieldOffset(String owner, String name) {
        Integer off = fieldOffsets.get(owner + "#" + name);
        if (off != null) {
            return off;
        }
        for (String c : fieldChain(owner)) {
            Integer o = fieldOffsets.get(c + "#" + name);
            if (o != null) {
                return o;
            }
        }
        throw new AotException("unknown field " + owner + "." + name);
    }

    public int objectSize(String cls) {
        Integer s = objectSizes.get(cls);
        if (s == null) {
            throw new AotException("unknown class size " + cls);
        }
        return s;
    }

    public List<String> superChain(String cls) {
        return fieldChain(cls);
    }

    public String vtableLabel(String cls) {
        return "@vtable_" + ClassParser.mangle(cls, "v", "()V");
    }

    public String staticLabel(FieldInsnNode f) {
        String key = "@st_" + ClassParser.mangle(f.owner, f.name, f.desc);
        staticFields.computeIfAbsent(key, k -> k);
        return key;
    }

    public String renderStatics() {
        StringBuilder sb = new StringBuilder();
        for (String key : staticFields.keySet()) {
            sb.append(key).append(" = global i64 0\n");
        }
        return sb.toString();
    }

    public String renderRootTable(StringPool pool) {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> roots = new ArrayList<>();
        roots.add("@.pending_exc");
        roots.addAll(staticFields.keySet());
        for (int i = 0; i < pool.utf16Values().size(); i++) {
            roots.add("@.strcache_" + i);
        }
        sb.append("@.root_slots = global [").append(roots.size()).append(" x ptr] [");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("ptr ").append(roots.get(i));
        }
        sb.append("]\n");
        return sb.toString();
    }

    public int rootCount(StringPool pool) {
        return staticFields.size() + 1 + pool.utf16Values().size();
    }

    public String resolveImplementation(String cls, String name, String desc) {
        String cur = cls;
        while (cur != null && classes.containsKey(cur)) {
            ClassInfo c = classes.get(cur);
            MethodNode mn = c.findMethod(name, desc);
            if (mn != null && !ClassParser.isStatic(mn)) {
                return cur;
            }
            cur = c.superName();
        }
        return null;
    }

    public String renderVtables() {
        StringBuilder sb = new StringBuilder();
        sb.append("@.vt_empty = private unnamed_addr constant [1 x ptr] [ptr null]\n");
        int n = Math.max(slotCount(), 1);
        for (String cls : classes.keySet()) {
            sb.append(vtableLabel(cls)).append(" = global [").append(n).append(" x ptr] [");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String impl = i < slotCount() ? resolveSlot(cls, slotKeys.get(i)) : null;
                sb.append(impl == null ? "ptr null" : "ptr @" + impl);
            }
            sb.append("]\n");
        }
        return sb.toString();
    }

    public int vtableSlotOffset(int slot) {
        return slot * 8;
    }

    private String resolveSlot(String cls, String key) {
        int paren = key.indexOf('(');
        String name = key.substring(0, paren);
        String desc = key.substring(paren);
        String owner = resolveImplementation(cls, name, desc);
        if (owner == null) {
            return null;
        }
        return ClassParser.mangle(owner, name, desc);
    }

    public Map<String, Integer> slotMap() {
        return slots;
    }

    public boolean hasSlot(String name, String desc) {
        return slots.containsKey(name + desc);
    }

    public String renderClassNameTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("@.class_names = global [").append(maxTypeId() + 1).append(" x i64] zeroinitializer\n");
        for (Map.Entry<String, Integer> e : typeIds.entrySet()) {
            String dotted = displayName(e.getKey());
            sb.append("@.cname_").append(e.getValue()).append(" = private constant [")
                .append(dotted.length()).append(" x i8] c\"")
                .append(escapeAsmString(dotted)).append("\"\n");
        }
        return sb.toString();
    }

    public List<String> classNameInit(StringPool pool) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : typeIds.entrySet()) {
            String dotted = displayName(e.getKey());
            out.add("  %cn" + e.getValue() + " = call i64 @aot_str_from_utf8(i64 "
                + "ptrtoint (ptr @.cname_" + e.getValue() + " to i64), i32 " + dotted.length() + ")");
            out.add("  %cnp" + e.getValue() + " = getelementptr [" + (maxTypeId() + 1) + " x i64], ptr @.class_names, i64 0, i32 "
                + e.getValue());
            out.add("  store i64 %cn" + e.getValue() + ", ptr %cnp" + e.getValue());
        }
        return out;
    }

    private static String displayName(String internal) {
        String dotted = internal.replace('/', '.');
        if (dotted.length() == 1) {
            return "[" + dotted;
        }
        return dotted;
    }

    public int maxTypeId() {
        int max = 0;
        for (Integer v : typeIds.values()) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    private static String escapeAsmString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127 && c != '"' && c != '\\') {
                sb.append(c);
            } else {
                sb.append(String.format("\\%02X", (int) c));
            }
        }
        return sb.toString();
    }

    public static final class FieldMeta {
        public final String owner;
        public final String name;
        public final String desc;
        public final int offset;
        public final boolean isStatic;

        FieldMeta(String owner, String name, String desc, int offset, boolean isStatic) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.offset = offset;
            this.isStatic = isStatic;
        }
    }

    public List<FieldMeta> fieldMetas() {
        List<FieldMeta> out = new ArrayList<>();
        for (ClassInfo c : classes.values()) {
            List<String> chain = fieldChain(c.name());
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (int i = chain.size() - 1; i >= 0; i--) {
                ClassInfo owner = classes.get(chain.get(i));
                if (owner == null) {
                    continue;
                }
                for (FieldInfo f : owner.fields()) {
                    if (!seen.add(f.name())) {
                        continue;
                    }
                    String key = owner.name() + "#" + f.name();
                    Integer off = fieldOffsets.get(key);
                    out.add(new FieldMeta(c.name(), f.name(), f.desc(),
                        off == null ? -1 : off, f.isStatic()));
                }
            }
        }
        return out;
    }

    public static int retCode(String methodDesc) {
        String r = org.objectweb.asm.Type.getReturnType(methodDesc).getDescriptor();
        if ("V".equals(r)) {
            return 0;
        }
        return kindCode(r);
    }

    public static int kindCode(String desc) {
        char c = desc.charAt(0);
        return switch (c) {
            case 'Z', 'B' -> 1;
            case 'S' -> 2;
            case 'C', 'I' -> 3;
            case 'F' -> 4;
            case 'J' -> 6;
            case 'D' -> 7;
            default -> 5;
        };
    }
}