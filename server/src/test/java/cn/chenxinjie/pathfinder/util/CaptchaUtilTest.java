package cn.chenxinjie.pathfinder.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片验证码生成工具（PRD F1 安全要求，对应 TC-LOGIN-001/003）：
 * 4 位随机码仅含不易混淆字符集；生成的 base64 为合法 PNG；随机码长度稳定。
 */
class CaptchaUtilTest {

    private static final char[] ALLOWED =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static Set<Character> allowedSet() {
        Set<Character> set = new HashSet<>();
        for (char c : ALLOWED) {
            set.add(c);
        }
        return set;
    }

    @Test
    void randomCode_length4AndWithinSafeCharset() {
        Set<Character> allowed = allowedSet();
        for (int i = 0; i < 200; i++) {
            String code = CaptchaUtil.randomCode(4);
            assertEquals(4, code.length());
            for (char c : code.toCharArray()) {
                assertTrue(allowed.contains(c), "字符应在安全字符集内，实际: " + c);
            }
        }
    }

    @Test
    void randomCode_respectsRequestedLength() {
        assertEquals(6, CaptchaUtil.randomCode(6).length());
    }

    @Test
    void imageBase64_decodesToPng() {
        String image = CaptchaUtil.imageBase64("A1B2");
        assertFalse(image.isBlank());
        byte[] raw = Base64.getDecoder().decode(image);
        // PNG 魔数
        assertTrue(raw.length > 8, "PNG 应含文件头");
        assertEquals((byte) 0x89, raw[0]);
        assertEquals('P', raw[1]);
        assertEquals('N', raw[2]);
        assertEquals('G', raw[3]);
    }

    @Test
    void imageBase64_emptyCodeStillProducesValidPng() {
        String image = CaptchaUtil.imageBase64("");
        byte[] raw = Base64.getDecoder().decode(image);
        assertEquals((byte) 0x89, raw[0]);
        assertTrue(raw.length > 0);
    }
}
