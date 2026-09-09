package com.cloudx.biz.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 加解密工具 — 用于 Secret Key 加密存储
 * 密钥从环境变量 SECRET_ENCRYPT_KEY 读取，默认值仅用于本地开发
 */
public final class AesUtil {

    private static final String ALGORITHM = "AES";
    private static final byte[] DEFAULT_KEY = "cloudx-2026-aes!".getBytes(StandardCharsets.UTF_8); // 16 bytes
    private static final byte[] KEY;

    static {
        String envKey = System.getenv("SECRET_ENCRYPT_KEY");
        if (envKey != null && !envKey.isBlank()) {
            byte[] bytes = envKey.getBytes(StandardCharsets.UTF_8);
            // 确保 16 字节
            if (bytes.length == 16) {
                KEY = bytes;
            } else {
                KEY = new byte[16];
                System.arraycopy(bytes, 0, KEY, 0, Math.min(bytes.length, 16));
            }
        } else {
            KEY = DEFAULT_KEY;
        }
    }

    private AesUtil() {}

    /** 加密 */
    public static String encrypt(String plainText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed", e);
        }
    }

    /** 解密 */
    public static String decrypt(String encryptedText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }
}