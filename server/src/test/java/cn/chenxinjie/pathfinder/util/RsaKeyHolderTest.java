package cn.chenxinjie.pathfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import javax.crypto.Cipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RsaKeyHolder 测试：加载持久化私钥时公钥必须与私钥配对（回归修复）。
 */
class RsaKeyHolderTest {

    @TempDir
    Path tempDir;

    private Path writePrivateKeyFile() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Path f = tempDir.resolve("private.pem");
        Files.write(f, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()).getBytes());
        return f;
    }

    @Test
    void loadFromPersistedPrivateKey_publicKeyMatches() throws Exception {
        Path keyFile = writePrivateKeyFile();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pathfinder.security.private-key-path", keyFile.toString());
        RsaKeyHolder holder = new RsaKeyHolder(env);

        // 用 holder 暴露的公钥加密，再解密应成功（公钥与私钥配对）
        byte[] cipher = encrypt(holder.publicKeyBase64(), "secret-pass");
        assertEquals("secret-pass", new String(holder.decrypt(cipher)));
    }

    @Test
    void decrypt_throwsBizOnWrongKey() throws Exception {
        Path keyFile = writePrivateKeyFile();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pathfinder.security.private-key-path", keyFile.toString());
        RsaKeyHolder holder = new RsaKeyHolder(env);

        // 用另一对随机公钥加密 → 当前私钥无法解密
        KeyPair other = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        byte[] cipher = encrypt(Base64.getEncoder().encodeToString(other.getPublic().getEncoded()), "x");
        cn.chenxinjie.pathfinder.service.BizException ex = assertThrows(
                cn.chenxinjie.pathfinder.service.BizException.class, () -> holder.decrypt(cipher));
        assertEquals(400, ex.getStatus());
    }

    @Test
    void withoutKeyFile_generatesAndExposesPublicKey() {
        MockEnvironment env = new MockEnvironment();
        RsaKeyHolder holder = new RsaKeyHolder(env);
        assertNotNull(holder.publicKeyBase64());
    }

    private byte[] encrypt(String publicKeyBase64, String plain) throws Exception {
        byte[] der = Base64.getDecoder().decode(publicKeyBase64);
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(der);
        var pub = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        return cipher.doFinal(plain.getBytes());
    }
}
