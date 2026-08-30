package com.pathfinder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathUtilTest {

    @Test
    void extension_returnsLowercaseSuffix() {
        assertEquals("pdf", PathUtil.extension("report.PDF"));
        assertEquals("gz", PathUtil.extension("a.tar.gz"));
        assertEquals("", PathUtil.extension("noext"));
        assertEquals("", PathUtil.extension(".hidden"));
    }

    @Test
    void relativeStorePath_containsDateDirAndUuid() {
        String rel = PathUtil.relativeStorePath("hello.txt");
        assertTrue(rel.startsWith("files/"));
        assertTrue(rel.endsWith(".txt"));
        assertTrue(rel.contains("/"));
    }

    @Test
    void uniqueName_appendsIndexBeforeExtension() {
        assertEquals("xxx(1).pdf", PathUtil.uniqueName("xxx.pdf", 1));
        assertEquals("a(2)", PathUtil.uniqueName("a", 2));
    }

    @Test
    void resolve_blocksPathTraversal() {
        var root = java.nio.file.Path.of("/data/storage");
        assertThrows(IllegalArgumentException.class,
                () -> PathUtil.resolve(root, "../evil.txt"));
    }

    @Test
    void uuid_isNonBlankHex() {
        String u = PathUtil.uuid();
        assertFalse(u.isBlank());
        assertTrue(u.matches("[0-9a-f]{32}"));
    }
}
