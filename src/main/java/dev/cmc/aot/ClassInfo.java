package dev.cmc.aot;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public record ClassInfo(String name, String superName, List<String> interfaces, int access,
    List<MethodNode> methods, List<FieldInfo> fields) {

    public MethodNode findMethod(String mName, String mDesc) {
        for (MethodNode mn : methods) {
            if (mn.name.equals(mName) && mn.desc.equals(mDesc)) {
                return mn;
            }
        }
        return null;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }
}