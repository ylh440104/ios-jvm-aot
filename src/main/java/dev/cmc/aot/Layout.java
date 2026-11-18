package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Layout {

    public static final int HEADER_SIZE = 8;

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, Integer> fieldOffsets = new HashMap<>();
    private final Map<String, Integer> objectSizes = new HashMap<>();
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final List<String> slotKeys = new ArrayList<>();
    private final Map<String, Integer> vtableIndex = new HashMap<>();

    private Layout() {
    }

    public static Layout build(List<ClassInfo> all) {
        Layout l = new Layout();
        for (ClassInfo c : all) {
            l.classes.put(c.name(), c);
        }
        l.assignSlots();
        l.assignFieldOffsets();
        l.buildVtables();
        return l;
    }

    private void assignSlots() {
        for (ClassInfo c : classes.values()) {
            for (MethodNode mn : c.methods()) {
                if (ClassParser.isStatic(mn) || ClassParser.isPrivate(mn) || ClassParser.isCtor(mn.name)) {
                    continue;
                }
                String key = mn.name + mn.desc;
                if (!slots.containsKey(key)) {
                    slots.put(key, slotKeys.size());
                    slotKeys.add(key);
                }
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
                    if (!fieldOffsets.containsKey(key)) {
                        fieldOffsets.put(key, offset);
                        offset += 8;
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

    public ClassInfo get(String name) {
        return classes.get(name);
    }

    public int slotCount() {
        return slotKeys.size();
    }

    public int slotOf(String name, String desc) {
        Integer i = slots.get(name + desc);
        if (i == null) {
            throw new AotException("no vtable slot for " + name + desc);
        }
        return i;
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
        for (String cls : classes.keySet()) {
            int n = Math.max(slotCount(), 1);
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
}