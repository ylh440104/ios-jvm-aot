package dev.cmc.aot;

import java.util.ArrayList;
import java.util.List;

public final class TypeUtil {

    private TypeUtil() {
    }

    public static List<String> argKinds(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == ')') {
                break;
            }
            if (c == '[') {
                while (i < desc.length() && desc.charAt(i) == '[') {
                    i++;
                }
                if (i < desc.length() && desc.charAt(i) == 'L') {
                    int semi = desc.indexOf(';', i);
                    i = semi < 0 ? desc.length() : semi + 1;
                } else {
                    i++;
                }
                out.add("L");
                continue;
            }
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                i = semi < 0 ? desc.length() : semi + 1;
                out.add("L");
                continue;
            }
            if (c == 'J' || c == 'D') {
                throw new AotException("long/double parameters are not supported yet: " + desc);
            }
            out.add("I");
            i++;
        }
        return out;
    }

    public static String returnKind(String desc) {
        int r = desc.indexOf(')');
        if (r < 0 || r + 1 >= desc.length()) {
            return "V";
        }
        char c = desc.charAt(r + 1);
        if (c == 'V') {
            return "V";
        }
        if (c == 'L' || c == '[') {
            return "L";
        }
        if (c == 'J' || c == 'D') {
            return "J";
        }
        return "I";
    }
}