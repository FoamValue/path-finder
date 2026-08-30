package com.pathfinder.util;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 存储路径工具：UUID 命名 + 日期分目录。
 */
public final class PathUtil {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private PathUtil() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String extension(String originalName) {
        int idx = originalName.lastIndexOf('.');
        if (idx > 0 && idx < originalName.length() - 1) {
            return originalName.substring(idx + 1).toLowerCase();
        }
        return "";
    }

    /** files/{yyyy-MM-dd}/{uuid}.{ext} 相对路径 */
    public static String relativeStorePath(String originalName) {
        String ext = extension(originalName);
        String name = uuid() + (ext.isEmpty() ? "" : "." + ext);
        return "files/" + LocalDate.now().format(DAY) + "/" + name;
    }

    public static Path resolve(Path root, String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IllegalArgumentException("非法路径穿越: " + relative);
        }
        return p;
    }

    /** 同名文件追加序号：xxx(1).pdf */
    public static String uniqueName(String name, long index) {
        int idx = name.lastIndexOf('.');
        if (idx > 0) {
            return name.substring(0, idx) + "(" + index + ")" + name.substring(idx);
        }
        return name + "(" + index + ")";
    }
}
