package dev.cmc.aot;

import java.util.List;

final class ReflectSupport {

    private ReflectSupport() {
    }

    static String renderMeta(Layout layout) {
        List<Layout.FieldMeta> metas = layout.fieldMetas();
        StringBuilder sb = new StringBuilder();
        int slots = Math.max(metas.size() * 5, 5);
        sb.append("@.field_meta = global [").append(slots).append(" x i64] zeroinitializer\n");
        int i = 0;
        for (Layout.FieldMeta m : metas) {
            sb.append("@.fname_").append(i).append(" = private constant [").append(m.name.length())
                .append(" x i8] c\"").append(m.name).append("\"\n");
            i++;
        }
        return sb.toString();
    }

    static String renderMetaInit(Layout layout) {
        List<Layout.FieldMeta> metas = layout.fieldMetas();
        StringBuilder sb = new StringBuilder();
        int slabs = Math.max(metas.size() * 5, 5);
        int i = 0;
        for (Layout.FieldMeta m : metas) {
            sb.append("  %fm").append(i).append(" = call i64 @aot_str_from_utf8(i64 ")
                .append("ptrtoint (ptr @.fname_").append(i).append(" to i64), i32 ")
                .append(m.name.length()).append(")\n");
            String[][] cells = {
                { "0", String.valueOf(layout.typeId(m.owner)) },
                { "1", String.valueOf(m.offset) },
                { "2", String.valueOf(Layout.kindCode(m.desc)) },
                { "3", m.isStatic ? "1" : "0" },
            };
            for (String[] cell : cells) {
                sb.append("  %f").append(cell[0]).append("_").append(i).append(" = getelementptr [")
                    .append(slabs).append(" x i64], ptr @.field_meta, i64 0, i32 ")
                    .append(i * 5 + Integer.parseInt(cell[0])).append("\n");
                sb.append("  store i64 ").append(cell[1]).append(", ptr %f").append(cell[0])
                    .append("_").append(i).append("\n");
            }
            sb.append("  %f4_").append(i).append(" = getelementptr [").append(slabs)
                .append(" x i64], ptr @.field_meta, i64 0, i32 ").append(i * 5 + 4).append("\n");
            sb.append("  store i64 %fm").append(i).append(", ptr %f4_").append(i).append("\n");
            i++;
        }
        sb.append("  call void @aot_set_field_meta_table(ptr @.field_meta, i32 ").append(metas.size()).append(")\n");
        return sb.toString();
    }
}