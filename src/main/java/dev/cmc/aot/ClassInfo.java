package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public record ClassInfo(String name, String superName, List<MethodNode> methods) {

    public boolean isInterface() {
        return false;
    }
}