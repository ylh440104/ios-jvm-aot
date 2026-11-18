package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public record ClassInfo(String name, String superName, List<MethodNode> methods, List<FieldInfo> fields) {

    public MethodNode findMethod(String mName, String mDesc) {
        for (MethodNode mn : methods) {
            if (mn.name.equals(mName) && mn.desc.equals(mDesc)) {
                return mn;
            }
        }
        return null;
    }
}