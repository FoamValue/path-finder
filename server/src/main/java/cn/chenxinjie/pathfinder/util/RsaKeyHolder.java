package cn.chenxinjie.pathfinder.util;

import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * RSA 密钥对：优先从配置的私钥文件加载，否则启动时生成并持久化，保证重启沿用。
 */
@Component
public class RsaKeyHolder {

    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    public RsaKeyHolder(org.springframework.core.env.Environment env) {
        String path = env.getProperty("pathfinder.security.private-key-path", "");
        KeyPair pair = loadOrGenerate(path);
        this.publicKey = pair.getPublic();
        this.privateKey = pair.getPrivate();
    }

    private KeyPair loadOrGenerate(String path) {
        try {
            if (path != null && !path.isBlank()) {
                java.nio.file.Path f = java.nio.file.Path.of(path);
                if (java.nio.file.Files.exists(f)) {
                    byte[] der = Base64.getDecoder().decode(
                            new String(java.nio.file.Files.readAllBytes(f)).trim());
                    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
                    PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(spec);
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                    gen.initialize(2048);
                    PublicKey pub = gen.generateKeyPair().getPublic();
                    return new KeyPair(pub, pk);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("RSA 私钥文件加载失败: " + e.getMessage(), e);
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            persist(path, pair);
            return pair;
        } catch (Exception e) {
            throw new IllegalStateException("RSA 密钥对生成失败", e);
        }
    }

    private void persist(String path, KeyPair pair) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path f = java.nio.file.Path.of(path);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f,
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()).getBytes());
        } catch (Exception ignore) {
            // 持久化失败不阻断启动（仅影响重启沿用）
        }
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 使用私钥解密密文（BASE64），返回明文字节。
     */
    public byte[] decrypt(byte[] cipherData) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(cipherData);
        } catch (Exception e) {
            throw new cn.chenxinjie.pathfinder.service.BizException(400, "密码解密失败，请重新获取公钥后重试");
        }
    }
}
