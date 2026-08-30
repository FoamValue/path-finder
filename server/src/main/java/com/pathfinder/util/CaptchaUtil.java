package com.pathfinder.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图片验证码生成工具。
 */
public final class CaptchaUtil {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final char[] CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private CaptchaUtil() {
    }

    public static String randomCode(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(CHARS[ThreadLocalRandom.current().nextInt(CHARS.length)]);
        }
        return sb.toString();
    }

    /**
     * 生成带干扰线的验证码 PNG，返回 base64（data 部分）。
     */
    public static String imageBase64(String code) {
        try {
            BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(245, 245, 245));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setFont(new Font("Arial", Font.BOLD, 28));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(ThreadLocalRandom.current().nextInt(120),
                        ThreadLocalRandom.current().nextInt(120),
                        ThreadLocalRandom.current().nextInt(120)));
                g.drawString(String.valueOf(code.charAt(i)), 12 + i * 26,
                        28 + ThreadLocalRandom.current().nextInt(6));
            }
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(ThreadLocalRandom.current().nextInt(200),
                        ThreadLocalRandom.current().nextInt(200),
                        ThreadLocalRandom.current().nextInt(200)));
                g.drawLine(ThreadLocalRandom.current().nextInt(WIDTH),
                        ThreadLocalRandom.current().nextInt(HEIGHT),
                        ThreadLocalRandom.current().nextInt(WIDTH),
                        ThreadLocalRandom.current().nextInt(HEIGHT));
            }
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("验证码生成失败", e);
        }
    }
}
