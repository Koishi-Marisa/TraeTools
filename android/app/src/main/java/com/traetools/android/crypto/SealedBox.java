package com.traetools.android.crypto;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * libsodium {@code crypto_box_seal} 的纯 JVM 实现（与 GitHub Actions Secrets 的加密要求兼容）。
 *
 * 算法（与 libsodium 完全一致）：
 * <pre>
 *   epk        = X25519_base(esk)                       // 一次性临时公钥
 *   nonce      = BLAKE2b-192(epk || pk)                 // 24 字节
 *   shared     = X25519(esk, pk)
 *   key        = HSalsa20(shared, 0^16)
 *   ciphertext = crypto_secretbox(msg, nonce, key)      // XSalsa20-Poly1305，前 16 字节为 tag
 *   sealed     = epk || ciphertext                      // 比明文长 48 字节
 * </pre>
 *
 * X25519 与 BLAKE2b 复用 BouncyCastle（纯 Java，无 native 依赖），Salsa20/Poly1305 见同包实现。
 */
public final class SealedBox {

    /** 公钥/私钥字节数。 */
    public static final int PUBLIC_KEY_BYTES = 32;
    /** sealed box 相比明文的固定开销：16 字节临时公钥之外的 32 字节（epk32 + tag16）。 */
    public static final int SEAL_BYTES = 48;

    private static final byte[] ZERO_NONCE_16 = new byte[16];
    private static final SecureRandom RANDOM = new SecureRandom();

    private SealedBox() {
    }

    /** 用接收方公钥封装消息（随机临时密钥）。 */
    public static byte[] seal(byte[] message, byte[] recipientPublicKey) {
        byte[] ephemeralSecret = new byte[PUBLIC_KEY_BYTES];
        RANDOM.nextBytes(ephemeralSecret);
        return sealDeterministic(message, recipientPublicKey, ephemeralSecret);
    }

    /** 使用指定临时私钥封装（确定性路径，便于测试对照 libsodium）。 */
    static byte[] sealDeterministic(byte[] message, byte[] recipientPublicKey, byte[] ephemeralSecretKey) {
        require(recipientPublicKey.length == PUBLIC_KEY_BYTES, "公钥长度必须为 32 字节");
        require(ephemeralSecretKey.length == PUBLIC_KEY_BYTES, "临时私钥长度必须为 32 字节");

        byte[] ephemeralPublic = x25519Base(ephemeralSecretKey);
        byte[] nonce = blake2b(concat(ephemeralPublic, recipientPublicKey), 24);
        byte[] key = beforeNm(recipientPublicKey, ephemeralSecretKey);
        byte[] ciphertext = secretBox(message, nonce, key);
        return concat(ephemeralPublic, ciphertext);
    }

    /** 用接收方私钥解封（主要用于自测/校验；App 运行时不需要）。 */
    public static byte[] open(byte[] sealed, byte[] recipientSecretKey) {
        require(sealed.length >= SEAL_BYTES, "sealed box 长度不足");
        byte[] ephemeralPublic = Arrays.copyOfRange(sealed, 0, PUBLIC_KEY_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(sealed, PUBLIC_KEY_BYTES, sealed.length);

        byte[] recipientPublic = x25519Base(recipientSecretKey);
        byte[] nonce = blake2b(concat(ephemeralPublic, recipientPublic), 24);
        byte[] key = beforeNm(ephemeralPublic, recipientSecretKey);

        byte[] keystream = Salsa20.xsalsa20Keystream(key, nonce, 32 + ciphertext.length);
        byte[] polyKey = Arrays.copyOfRange(keystream, 0, 32);
        byte[] tag = Arrays.copyOfRange(ciphertext, 0, 16);
        byte[] body = Arrays.copyOfRange(ciphertext, 16, ciphertext.length);
        byte[] expectTag = Poly1305.auth(body, polyKey);
        require(Arrays.equals(tag, expectTag), "sealed box 认证失败（tag 不匹配）");

        byte[] message = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            message[i] = (byte) (body[i] ^ keystream[32 + i]);
        }
        return message;
    }

    // ---------- 内部原语 ----------

    /** crypto_box_beforenm：X25519 共享密钥再经 HSalsa20 归零 nonce 派生。 */
    static byte[] beforeNm(byte[] recipientPublicKey, byte[] senderSecretKey) {
        byte[] shared = x25519(senderSecretKey, recipientPublicKey);
        return Salsa20.hsalsa20(shared, ZERO_NONCE_16, 0);
    }

    /** crypto_secretbox：XSalsa20-Poly1305，返回 tag(16) || 密文。 */
    static byte[] secretBox(byte[] message, byte[] nonce24, byte[] key) {
        byte[] keystream = Salsa20.xsalsa20Keystream(key, nonce24, 32 + message.length);
        byte[] polyKey = Arrays.copyOfRange(keystream, 0, 32);
        byte[] cipher = new byte[message.length];
        for (int i = 0; i < message.length; i++) {
            cipher[i] = (byte) (message[i] ^ keystream[32 + i]);
        }
        byte[] tag = Poly1305.auth(cipher, polyKey);
        return concat(tag, cipher);
    }

    static byte[] x25519(byte[] scalar, byte[] uCoordinate) {
        byte[] out = new byte[PUBLIC_KEY_BYTES];
        X25519.scalarMult(scalar, 0, uCoordinate, 0, out, 0);
        return out;
    }

    static byte[] x25519Base(byte[] scalar) {
        byte[] out = new byte[PUBLIC_KEY_BYTES];
        X25519.scalarMultBase(scalar, 0, out, 0);
        return out;
    }

    static byte[] blake2b(byte[] data, int outLen) {
        Blake2bDigest digest = new Blake2bDigest(outLen * 8);
        digest.update(data, 0, data.length);
        byte[] out = new byte[outLen];
        digest.doFinal(out, 0);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}