package dev.cmc.aot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public final class ClassParser {

    private ClassParser() {
    }

    public static ClassInfo parse(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        List<MethodNode> methods = new ArrayList<>();
        for (MethodNode mn : node.methods) {
            if (mn.instructions != null && mn.instructions.size() > 0) {
                methods.add(mn);
            }
        }
        return new ClassInfo(node.name, node.superName, methods);
    }

    public static boolean isStatic(MethodNode mn) {
        return (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
    }

    public static String mangle(String owner, String name, String desc) {
        return sanitize(owner) + "_" + sanitize(name) + "_" + Integer.toHexString(desc.hashCode());
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}