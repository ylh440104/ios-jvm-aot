package dev.cmc.aot;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class Coverage {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path out = args.length > 1 ? Path.of(args[1]) : Path.of("coverage.txt");
        String mode = args.length > 2 ? args[2] : "whole";
        List<ClassInfo> all = new ArrayList<>();
        int unparsable = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                try {
                    all.add(ClassParser.parse(Files.readAllBytes(p)));
                } catch (Throwable t) {
                    unparsable++;
                }
            }
        }

        PrintStream origErr = System.err;
        System.setErr(new PrintStream(OutputStreamSink.INSTANCE));

        StringBuilder sb = new StringBuilder();
        if ("whole".equals(mode)) {
            runWhole(all, sb);
        } else {
            runPerClass(all, sb);
        }

        System.setErr(origErr);
        sb.append("unparsable=").append(unparsable).append('\n');
        Files.writeString(out, sb.toString());
        System.out.print(sb);
    }

    private static void runWhole(List<ClassInfo> all, StringBuilder sb) {
        LambdaSynthesizer.scan(all);
        Layout layout = Layout.build(all);
        layout.typeId("java/lang/Class");
        layout.typeId("java/lang/String");
        layout.typeId("java/lang/Integer");
        layout.typeId("java/lang/Long");
        StringPool pool = new StringPool();
        Set<String> declares = new LinkedHashSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int total = 0;
        int ok = 0;
        Set<String> failedClasses = new LinkedHashSet<>();
        for (ClassInfo c : all) {
            for (var mn : c.methods()) {
                total++;
                try {
                    MethodTranslator.translate(c, mn, pool, declares, layout);
                    ok++;
                } catch (Throwable t) {
                    String msg = String.valueOf(t.getMessage());
                    if (msg.length() > 100) {
                        msg = msg.substring(0, 100);
                    }
                    reasons.merge(normalize(t, msg), 1, Integer::sum);
                    failedClasses.add(c.name());
                }
            }
        }
        emit(sb, "whole", total, ok, reasons);
        sb.append("failed_classes=").append(failedClasses.size()).append('\n');
    }

    private static void runPerClass(List<ClassInfo> all, StringBuilder sb) {
        Map<String, Integer> reasons = new TreeMap<>();
        int total = 0;
        int ok = 0;
        for (ClassInfo c : all) {
            LambdaSynthesizer.scan(List.of(c));
            Layout layout = Layout.build(List.of(c));
            layout.typeId("java/lang/Class");
            layout.typeId("java/lang/String");
            StringPool pool = new StringPool();
            Set<String> declares = new LinkedHashSet<>();
            for (var mn : c.methods()) {
                total++;
                try {
                    MethodTranslator.translate(c, mn, pool, declares, layout);
                    ok++;
                } catch (Throwable t) {
                    String msg = String.valueOf(t.getMessage());
                    if (msg.length() > 100) {
                        msg = msg.substring(0, 100);
                    }
                    reasons.merge(normalize(t, msg), 1, Integer::sum);
                }
            }
        }
        emit(sb, "per-class", total, ok, reasons);
    }

    private static String normalize(Throwable t, String msg) {
        if (msg == null || msg.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        int paren = msg.indexOf('(');
        String head = paren > 0 ? msg.substring(0, paren) : msg;
        if (head.startsWith("no vtable slot for ")) {
            return head;
        }
        if (head.startsWith("unknown class for new")) {
            return "unknown class for new <class>";
        }
        if (head.startsWith("unknown field")) {
            return "unknown field <owner.field>";
        }
        if (head.startsWith("lambda site not synthesised")) {
            return "lambda site not synthesised";
        }
        return head;
    }

    private static void emit(StringBuilder sb, String mode, int total, int ok, Map<String, Integer> reasons) {
        sb.append("mode=").append(mode).append('\n');
        sb.append("methods_total=").append(total).append('\n');
        sb.append("methods_ok=").append(ok).append('\n');
        double pct = total == 0 ? 0.0 : (100.0 * ok / total);
        sb.append(String.format("methods_ok_pct=%.2f%n", pct));
        sb.append("distinct_reasons=").append(reasons.size()).append('\n');
        sb.append('\n');
        List<Map.Entry<String, Integer>> top = new ArrayList<>(reasons.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Integer> e : top) {
            if (shown++ >= 40) {
                break;
            }
            sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        sb.append('\n');
    }

    static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}