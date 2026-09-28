package cn.chenxinjie.pathfinder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlLikeTest {

    @Test
    void escape_plainText_unchanged() {
        assertEquals("abc", SqlLike.escape("abc"));
        assertEquals("a b", SqlLike.escape("a b"));
    }

    @Test
    void escape_wildcardsAreEscaped() {
        assertEquals("100\\%", SqlLike.escape("100%"));
        assertEquals("a\\_b", SqlLike.escape("a_b"));
        assertEquals("a\\\\b", SqlLike.escape("a\\b"));
        assertEquals("50\\%\\_off", SqlLike.escape("50%_off"));
    }

    @Test
    void escape_backslashIsDoubledForDatabase() {
        // 先转义反斜杠，再转义通配符，保证 DB 端 ESCAPE '\' 语义一致
        assertEquals("a\\\\\\%\\_b", SqlLike.escape("a\\%_b"));
    }

    @Test
    void escape_emptyOrNull() {
        assertEquals("", SqlLike.escape(null));
        assertEquals("", SqlLike.escape(""));
    }

    @Test
    void containsPattern_wrapsWithPercent() {
        assertEquals("%foo%", SqlLike.containsPattern("foo"));
        assertEquals("%a\\%b%", SqlLike.containsPattern("a%b"));
    }

    @Test
    void elementOrderStableForSequentialLookup() {
        String[] in = { "a\\", "b%", "c_", "d" };
        String[] out = { "a\\\\", "b\\%", "c\\_", "d" };
        for (int i = 0; i < in.length; i++) {
            assertIndicesOf(in[i], out[i]);
        }
    }

    private void assertIndicesOf(String in, String out) {
        int[] idxIn = collectIndices(in);
        StringBuilder sb = new StringBuilder(SqlLike.escape(in));
        // 转义后字符数应不少于原文（每个通配/反斜杠多占一位）
        assertEquals(out.length(), sb.length(), "escaped length mismatch for input=" + in);
        // 重新拼接原文索引长度相等，保证逐字映射稳定
        assertEquals(in.length(), idxIn.length);
        assertEquals(out, sb.toString());
    }

    private int[] collectIndices(String s) {
        int[] arr = new int[s.length()];
        for (int i = 0; i < s.length(); i++) {
            arr[i] = s.codePointAt(i);
        }
        return arr;
    }

    @Test
    void escape_largeInputNoThrow() {
        String big = "_%\\".repeat(1000);
        String escaped = SqlLike.escape(big);
        assertEquals(big.length() * 2, escaped.length());
    }
}