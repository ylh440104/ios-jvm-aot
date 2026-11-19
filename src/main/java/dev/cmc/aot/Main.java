package dev.cmc.aot;

import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class Main {

    public static void main(String[] args) throws IOException {
        Path cp = null;
        Path out = Path.of("out.ll");
        String entryClass = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cp" -> cp = Path.of(args[++i]);
                case "-o" -> out = Path.of(args[++i]);
                case "-entry" -> entryClass = args[++i];
                default -> {
                    System.err.println("unknown arg " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (cp == null) {
            System.err.println("usage: ios-jvm-aot -cp <classes-dir> [-o out.ll]");
            System.exit(2);
        }

        List<ClassInfo> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(cp)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                classes.add(ClassParser.parse(Files.readAllBytes(p)));
            }
        }

        StringPool pool = new StringPool();
        Set<String> declares = new LinkedHashSet<>();
        LambdaSynthesizer.scan(classes);
        Layout layout = Layout.build(classes);
        layout.typeId("java/lang/Class");
        layout.typeId("java/lang/String");
        layout.typeId("java/lang/Integer");
        layout.typeId("java/lang/Long");
        StringBuilder funcs = new StringBuilder();
        String entry = null;
        List<String> entryArgs = List.of();

        for (ClassInfo c : classes) {
            for (MethodNode mn : c.methods()) {
                funcs.append(MethodTranslator.translate(c, mn, pool, declares, layout)).append('\n');
                if ("main".equals(mn.name) && "([Ljava/lang/String;)V".equals(mn.desc) && ClassParser.isStatic(mn)) {
                    if (entryClass == null || entryClass.equals(c.name())) {
                        entry = ClassParser.mangle(c.name(), mn.name, mn.desc);
                        entryArgs = TypeInfo.argKinds(mn.desc);
                    }
                }
            }
        }

        StringBuilder extraFuncs = new StringBuilder();
        extraFuncs.append(ThreadSupport.render(classes, layout));
        extraFuncs.append(ToStringSupport.render(classes, layout));

        StringBuilder ir = new StringBuilder();
        ir.append(pool.renderAll());
        ir.append(layout.renderVtables());
        ir.append(layout.renderStatics());
        ir.append("@.pending_exc = global i64 0\n");
        for (String lock : MethodTranslator.classLockNames()) {
            ir.append("@").append(lock).append(" = global i8 0\n");
        }
        ir.append("@gc_roots = global ptr null\n");
        ir.append("@gc_root_count = global i64 0\n");
        ir.append(layout.renderRootTable(pool));
        ir.append(pool.renderUtf16());
        ir.append(pool.renderStringSlots());
        declares.add("declare void @gc_init(i64)");
        declares.add("declare i64 @aot_str_from_utf16(i64, i32)");
        declares.add("declare i64 @aot_str_length(i64)");
        declares.add("declare i32 @aot_str_char_at(i64, i32)");
        declares.add("declare i32 @aot_str_equals(i64, i64)");
        declares.add("declare i32 @aot_str_hash(i64)");
        declares.add("declare i64 @aot_str_concat2(i64, i64)");
        declares.add("declare i64 @aot_str_of_int(i32)");
        declares.add("declare i64 @aot_str_of_long(i64)");
        declares.add("declare i64 @aot_str_of_double(double)");
        declares.add("declare void @aot_str_print(i64)");
        declares.add("declare i64 @aot_str_substring(i64, i32, i32)");
        declares.add("declare i32 @aot_str_index_of(i64, i32, i32)");
        declares.add("declare i32 @aot_str_compare_to(i64, i64)");
        declares.add("declare i64 @aot_object_hash(i64)");
        declares.add("declare i64 @aot_sb_new()");
        declares.add("declare i32 @aot_sb_length(i64)");
        declares.add("declare i64 @aot_sb_to_string(i64)");
        declares.add("declare i64 @aot_sb_reverse(i64)");
        declares.add("declare i64 @aot_sb_append_str(i64, i64)");
        declares.add("declare i64 @aot_sb_append_int(i64, i32)");
        declares.add("declare i64 @aot_sb_append_long(i64, i64)");
        declares.add("declare i64 @aot_sb_append_double(i64, double)");
        declares.add("declare i64 @aot_sb_append_float(i64, float)");
        declares.add("declare i64 @aot_sb_append_char(i64, i32)");
        declares.add("declare i64 @aot_sb_append_bool(i64, i32)");
        declares.add("declare void @aot_put_char(i32)");
        declares.add("declare i64 @aot_str_to_lower(i64)");
        declares.add("declare i64 @aot_str_to_upper(i64)");
        declares.add("declare i64 @aot_str_trim(i64)");
        declares.add("declare i32 @aot_str_starts_with(i64, i64)");
        declares.add("declare i32 @aot_str_ends_with(i64, i64)");
        declares.add("declare i32 @aot_str_contains(i64, i64)");
        declares.add("declare i32 @aot_str_index_of_str(i64, i64, i32)");
        declares.add("declare i64 @aot_str_replace(i64, i32, i32)");
        declares.add("declare i32 @aot_str_last_index_of(i64, i32)");
        declares.add("declare i64 @aot_str_of_char(i32)");
        declares.add("declare i64 @aot_str_of_bool(i32)");
        declares.add("declare i64 @aot_str_of_object(i64)");
        declares.add("declare i32 @aot_int_parse(i64)");
        declares.add("declare i64 @aot_long_parse(i64)");
        declares.add("declare i64 @aot_system_nano()");
        declares.add("declare i64 @aot_system_millis()");
        declares.add("declare void @aot_system_arraycopy(i64, i32, i64, i32, i32, i32)");
        declares.add("declare i64 @aot_arrays_copy_of_width(i64, i32, i32)");
        declares.add("declare void @gc_thread_attach()");
        declares.add("declare void @gc_thread_detach()");
        declares.add("declare i64 @aot_thread_new(i64)");
        declares.add("declare void @aot_thread_start(i64)");
        declares.add("declare void @aot_thread_join(i64)");
        declares.add("declare void @aot_thread_set_name(i64, i64)");
        declares.add("declare void @aot_thread_sleep(i64)");
        declares.add("declare void @aot_thread_yield()");
        declares.add("declare void @aot_dispatch_thread(i64)");
        declares.add("declare i64 @aot_thread_rec_new()");
        declares.add("declare void @aot_thread_set_body(i64, i64, i64)");
        declares.add("declare i32 @aot_monitor_enter(i64)");
        declares.add("declare i32 @aot_monitor_exit(i64)");
        declares.add("declare void @aot_system_gc()");
        declares.add("declare i64 @aot_list_new()");
        declares.add("declare i64 @aot_list_add(i64, i64)");
        declares.add("declare i64 @aot_list_get(i64, i32)");
        declares.add("declare i32 @aot_list_size(i64)");
        declares.add("declare i64 @aot_list_set(i64, i32, i64)");
        declares.add("declare i64 @aot_list_remove(i64, i32)");
        declares.add("declare i32 @aot_list_is_empty(i64)");
        declares.add("declare void @aot_list_clear(i64)");
        declares.add("declare i32 @aot_list_contains(i64, i64)");
        declares.add("declare i32 @aot_list_index_of(i64, i64)");
        declares.add("declare i64 @aot_list_to_array(i64)");
        declares.add("declare i64 @aot_map_new()");
        declares.add("declare i64 @aot_map_put(i64, i64, i64)");
        declares.add("declare i64 @aot_map_get(i64, i64)");
        declares.add("declare i64 @aot_map_remove(i64, i64)");
        declares.add("declare i32 @aot_map_size(i64)");
        declares.add("declare i32 @aot_map_contains(i64, i64)");
        declares.add("declare i64 @aot_map_keys(i64)");
        declares.add("declare i64 @aot_map_values(i64)");
        declares.add("declare void @aot_map_clear(i64)");
        declares.add("declare i64 @aot_arrays_sort(i64)");
        declares.add("declare i64 @aot_arrays_sort_int(i64)");
        declares.add("declare i32 @aot_arrays_equals_int(i64, i64)");
        declares.add("declare i32 @aot_arrays_equals(i64, i64)");
        declares.add("declare i64 @aot_arrays_copy_of(i64, i32)");
        declares.add("declare i64 @aot_box_new(i32)");
        declares.add("declare i32 @aot_box_int(i64)");
        declares.add("declare i64 @aot_long_box_new(i64)");
        declares.add("declare i64 @aot_long_box_get(i64)");
        declares.add("declare void @aot_set_string_type(i32)");
        declares.add("declare void @aot_set_class_type(i32)");
        declares.add("declare void @aot_set_class_table(ptr, i32)");
        declares.add("declare void @aot_set_integer_type(i32)");
        declares.add("declare void @aot_set_long_type(i32)");
        declares.add("declare i64 @aot_str_from_utf8(i64, i32)");
        declares.add("declare void @aot_set_field_type(i32)");
        declares.add("declare void @aot_set_method_type(i32)");
        declares.add("declare void @aot_set_field_meta_table(ptr, i32)");
        declares.add("declare void @aot_set_method_meta_table(ptr, i32)");
        declares.add("declare i64 @aot_object_array_ptr(i64)");
        String methodMetaDecl = MethodSupport.renderMethodMeta(layout);
        String methodMetaInit = MethodSupport.renderMethodMetaInit(layout);
        String methodTrampolines = MethodSupport.renderTrampolines(layout) + MethodSupport.renderDispatch(layout);
        String ctorMetaDecl = MethodSupport.renderCtorMeta(layout);
        String ctorMetaInit = MethodSupport.renderCtorMetaInit(layout);
        String ctorWrappers = MethodSupport.renderCtorWrappers(layout);
        String typeDescDecl = layout.renderTypeDescTable();
        String typeDescInit = layout.renderTypeDescInit();
        declares.add("declare void @aot_set_type_desc_table(ptr, i32)");
        declares.add("declare i64 @aot_ctor_desc_of(i64)");
        declares.add("declare i64 @aot_ctor_lookup(i32, i64)");
        declares.add("declare i64 @aot_ctor_new_instance(i64, i64)");
        declares.add("declare i64 @aot_class_declared_fields(i32)");
        declares.add("declare i64 @aot_class_declared_methods(i32)");
        declares.add("declare void @aot_set_ctor_type(i32)");
        declares.add("declare void @aot_set_ctor_meta_table(ptr, i32)");
        declares.add("declare void @aot_set_reflect_array_types(i32, i32)");
        declares.add("declare void @aot_set_char_array_type(i32)");
        declares.add("declare void @aot_set_string_array_type(i32)");
        declares.add("declare void @aot_set_char_box_type(i32)");
        declares.add("declare void @aot_set_bool_box_type(i32)");
        declares.add("declare i64 @aot_box_char(i32)");
        declares.add("declare i64 @aot_box_bool(i32)");
        declares.add("declare void @aot_set_assign_table(ptr, i32)");
        declares.add("declare void @aot_set_super_table(ptr, i32)");
        declares.add("declare void @aot_set_iface_table(ptr, i32)");
        declares.add("declare i64 @aot_class_get_superclass(i32)");
        declares.add("declare i32 @aot_class_is_assignable_from(i32, i32)");
        declares.add("declare i32 @aot_class_is_interface(i32)");
        declares.add("declare ptr @gc_calloc(i64, i64)");
        declares.add("declare void @aot_set_double_box_type(i32)");
        declares.add("declare void @aot_set_float_box_type(i32)");
        declares.add("declare i64 @aot_box_double(i64)");
        declares.add("declare i64 @aot_unbox_double(i64)");
        declares.add("declare i64 @aot_box_float(i32)");
        declares.add("declare i32 @aot_unbox_float(i64)");
        declares.add("declare i64 @aot_str_split(i64, i64)");
        declares.add("declare i64 @aot_str_to_char_array(i64)");
        declares.add("declare i64 @aot_str_repeat(i64, i32)");
        declares.add("declare i64 @aot_str_strip(i64)");
        declares.add("declare i32 @aot_str_region_equals(i64, i32, i64, i32, i32)");
        declares.add("declare void @aot_print_object(i64)");
        for (String d : declares) {
            ir.append(d).append('\n');
        }
        ir.append('\n').append(funcs);
        String reflectMetaInit = ReflectSupport.renderMetaInit(layout);
        int charArrayTid = layout.typeId("[C");
        int stringArrayTid = layout.typeId("[Ljava/lang/String;");
        int charBoxTid = layout.typeId("java/lang/Character");
        int boolBoxTid = layout.typeId("java/lang/Boolean");
        int doubleBoxTid = layout.typeId("java/lang/Double");
        int floatBoxTid = layout.typeId("java/lang/Float");
        String assignDecl = layout.renderAssignTable();
        String assignInit = layout.renderAssignInit();
        String superDecl = layout.renderSuperTable();
        String superInit = layout.renderSuperInit();
        String ifaceDecl = layout.renderIfaceTable();
        String ifaceInit = layout.renderIfaceInit();
        ir.append(layout.renderTypeTables());
        ir.append(layout.renderClassNameTable());
        ir.append(ReflectSupport.renderMeta(layout));
        ir.append(methodMetaDecl);
        ir.append(ctorMetaDecl);
        ir.append(typeDescDecl);
        ir.append(assignDecl);
        ir.append(superDecl);
        ir.append(ifaceDecl);
        ir.append(extraFuncs);
        ir.append(methodTrampolines);
        ir.append(ctorWrappers);
        ir.append("define i32 @main(i32 %argc, ptr %argv) {\nentry:\n");
        ir.append("  call void @gc_init(i64 268435456)\n");
        ir.append("  store ptr @.root_slots, ptr @gc_roots\n");
        ir.append("  store i64 ").append(layout.rootCount(pool)).append(", ptr @gc_root_count\n");
        ir.append("  call void @aot_set_string_type(i32 ").append(layout.typeId("java/lang/String")).append(")\n");
        ir.append("  call void @aot_set_char_array_type(i32 ").append(charArrayTid).append(")\n");
        ir.append("  call void @aot_set_string_array_type(i32 ").append(stringArrayTid).append(")\n");
        ir.append("  call void @aot_set_char_box_type(i32 ").append(charBoxTid).append(")\n");
        ir.append("  call void @aot_set_bool_box_type(i32 ").append(boolBoxTid).append(")\n");
        ir.append("  call void @aot_set_double_box_type(i32 ").append(doubleBoxTid).append(")\n");
        ir.append("  call void @aot_set_float_box_type(i32 ").append(floatBoxTid).append(")\n");
        ir.append("  call void @aot_set_integer_type(i32 ").append(layout.typeId("java/lang/Integer")).append(")\n");
        ir.append("  call void @aot_set_long_type(i32 ").append(layout.typeId("java/lang/Long")).append(")\n");
        ir.append(pool.renderStringInit());
        ir.append("  call void @aot_set_class_type(i32 ").append(layout.typeId("java/lang/Class")).append(")\n");
        for (String line : layout.classNameInit(pool)) {
            ir.append(line).append('\n');
        }
        ir.append("  call void @aot_set_class_table(ptr @.class_names, i32 ").append(layout.maxTypeId()).append(")\n");
        ir.append("  call void @aot_set_field_type(i32 ").append(layout.typeId("java/lang/reflect/Field")).append(")\n");
        ir.append("  call void @aot_set_method_type(i32 ").append(layout.typeId("java/lang/reflect/Method")).append(")\n");
        ir.append(reflectMetaInit);
        ir.append(methodMetaInit);
        ir.append(ctorMetaInit);
        ir.append(typeDescInit);
        ir.append(assignInit);
        ir.append(superInit);
        ir.append(ifaceInit);
        ir.append("  call void @aot_set_ctor_type(i32 ").append(layout.typeId("java/lang/reflect/Constructor")).append(")\n");
        ir.append("  call void @aot_set_reflect_array_types(i32 ").append(layout.typeId("[Ljava/lang/reflect/Field;")).append(", i32 ").append(layout.typeId("[Ljava/lang/reflect/Method;")).append(")\n");
        for (ClassInfo c : classes) {
            MethodNode cl = c.findMethod("<clinit>", "()V");
            if (cl != null) {
                ir.append("  %cl_").append(ClassParser.mangle(c.name(), "i", "()V"))
                    .append(" = call i32 @").append(ClassParser.mangle(c.name(), "<clinit>", "()V")).append("()\n");
            }
        }
        if (entry != null) {
            StringBuilder callArgs = new StringBuilder();
            for (int i = 0; i < entryArgs.size(); i++) {
                if (i > 0) {
                    callArgs.append(", ");
                }
                callArgs.append("I".equals(entryArgs.get(i)) ? "i32 0" : "i64 0");
            }
            ir.append("  %r = call i32 @").append(entry).append("(").append(callArgs).append(")\n");
        } else {
            ir.append("  %r = add i32 0, 0\n");
        }
        ir.append("  ret i32 %r\n}\n");

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, ir.toString());
        System.out.println("classes=" + classes.size() + " functions=" + countFuncs(funcs) + " output=" + out);
    }

    private static int countFuncs(StringBuilder sb) {
        int n = 0;
        int i = 0;
        while ((i = sb.indexOf("define i32 @", i)) >= 0) {
            n++;
            i += 11;
        }
        return n;
    }
}