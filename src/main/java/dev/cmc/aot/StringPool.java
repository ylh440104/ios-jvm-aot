package dev.cmc.aot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StringPool {

    private final Map<String, String> globals = new LinkedHashMap<>();
    private final List<String> literals = new ArrayList<>();
    private int seq;

    public String intern(String value) {
        return internRaw(value, false);
    }

    public String internFormat(String value) {
        return internRaw(value, true);
    }

    private String internRaw(String value, boolean raw) {
        String key = (raw ? "F:" : "S:") + value;
        String existing = globals.get(key);
        if (existing != null) {
            return existing;
        }
        String name = "@.str" + (seq++);
        globals.put(key, name);
        literals.add(render(name, value, raw));
        return name;
    }

    private String render(String name, String value, boolean raw) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" = private unnamed_addr constant [");
        StringBuilder bytes = new StringBuilder();
        int count = 0;
        if (raw) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 3 <= value.length()) {
                    String hex = value.substring(i + 1, i + 3);
                    if (hex.matches("[0-9A-Fa-f]{2}")) {
                        bytes.append("\\").append(hex.toUpperCase());
                        count++;
                        i += 2;
                        continue;
                    }
                }
                if (c == '\n') {
                    bytes.append("\\0A");
                    count++;
                } else if (c == '"' || c == '\\') {
                    bytes.append("\\").append(String.format("%02X", (int) c));
                    count++;
                } else if (c < 0x20 || c > 0x7E) {
                    byte[] utf = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    for (byte b : utf) {
                        bytes.append("\\").append(String.format("%02X", b & 0xFF));
                        count++;
                    }
                } else {
                    bytes.append(c);
                    count++;
                }
            }
        } else {
            byte[] utf = value.getBytes(StandardCharsets.UTF_8);
            for (byte b : utf) {
                int v = b & 0xFF;
                if (v >= 0x20 && v < 0x7F && v != '"' && v != '\\') {
                    bytes.append((char) v);
                } else {
                    bytes.append("\\").append(String.format("%02X", v));
                }
                count++;
            }
        }
        sb.append(count + 1).append(" x i8] c\"").append(bytes).append("\\00\"\n");
        return sb.toString();
    }

    public String renderAll() {
        StringBuilder sb = new StringBuilder();
        for (String l : literals) {
            sb.append(l);
        }
        return sb.toString();
    }

    private final Map<String, String> utf16Globals = new LinkedHashMap<>();
    private final List<String> utf16Literals = new ArrayList<>();
    private final List<String> utf16Order = new ArrayList<>();
    private int utf16Seq;

    public String internUtf16(String value) {
        String existing = utf16Globals.get(value);
        if (existing != null) {
            return existing;
        }
        String name = "@.u16_" + (utf16Seq++);
        utf16Globals.put(value, name);
        utf16Order.add(value);
        StringBuilder sb = new StringBuilder();
        int n = value.length();
        sb.append(name).append(" = private unnamed_addr constant [").append(Math.max(n, 1)).append(" x i16] [");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("i16 ").append((int) value.charAt(i));
        }
        if (n == 0) {
            sb.append("i16 0");
        }
        sb.append("]\n");
        utf16Literals.add(sb.toString());
        return name;
    }

    public String renderUtf16() {
        StringBuilder sb = new StringBuilder();
        for (String l : utf16Literals) {
            sb.append(l);
        }
        return sb.toString();
    }

    public List<String> utf16Values() {
        return utf16Order;
    }

    public String renderStringInit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < utf16Order.size(); i++) {
            String value = utf16Order.get(i);
            String label = utf16Globals.get(value);
            sb.append("  %si").append(i).append(" = ptrtoint ptr ").append(label).append(" to i64\n");
            sb.append("  %sv").append(i).append(" = call i64 @aot_str_from_utf16(i64 %si").append(i)
                .append(", i32 ").append(value.length()).append(")\n");
            sb.append("  store i64 %sv").append(i).append(", ptr @.strcache_").append(i).append("\n");
        }
        return sb.toString();
    }

    public String renderStringSlots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < utf16Order.size(); i++) {
            sb.append("@.strcache_").append(i).append(" = global i64 0\n");
        }
        return sb.toString();
    }

    public String slotFor(String value) {
        String g = utf16Globals.get(value);
        if (g == null) {
            return null;
        }
        int idx = utf16Order.indexOf(value);
        return "@.strcache_" + idx;
    }
}