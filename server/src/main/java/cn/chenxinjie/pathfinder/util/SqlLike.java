package cn.chenxinjie.pathfinder.util;

/**
 * LIKE 查询通配符转义：用户输入中的 %、_、\ 会被当作通配符/转义符，
 * 可能导致功能性越界。统一转义并以 ESCAPE '\' 语义拼接模糊模式。
 */
public final class SqlLike {

    private static final char ESCAPE = '\\';

    private SqlLike() {
    }

    /**
     * 转义单值，产出可直接嵌入 "%...%" 模式的内容。
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 生成前后模糊匹配模式，配合 criteria like(expr, pattern, ESCAPE) 使用。
     */
    public static String containsPattern(String value) {
        return "%" + escape(value) + "%";
    }
}