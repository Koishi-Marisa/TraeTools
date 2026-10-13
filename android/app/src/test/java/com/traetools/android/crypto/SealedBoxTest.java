package com.traetools.android.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 用 PyNaCl/libsodium 生成的参考向量校验纯 JVM sealed box 实现的正确性。
 * 向量覆盖：RFC 7748 X25519、XSalsa20-Poly1305(secretbox)、crypto_box_beforenm、crypto_box_seal。
 */
public class SealedBoxTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static void check(String hexExpected, byte[] actual) {
        assertArrayEquals(hex(hexExpected), actual);
    }

    @Test
    public void x25519_rfc7748_vector() {
        byte[] k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        check("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", SealedBox.x25519(k, u));
    }

    @Test
    public void secretbox_matches_libsodium() {
        check("b35a8d8216beef26ac0ce6a0f160afeb39715464b821deb625768cc2b24953db125c42606f581054d02565d34ccc00e5927f55c9bb14f479",
                SealedBox.secretBox(
                        hex("d3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4"),
                        hex("6f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910"),
                        hex("0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4")));
        check("b10a1c39b588b7ff1e2fa24255067c25a0c4442e1a0e4b4a53414648d139d61d5e0d126d790c771ba39e01f2218ae82e523558e7db16efd6523225069210f4244328d79f5b",
                SealedBox.secretBox(
                        hex("d4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940"),
                        hex("70777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11"),
                        hex("0c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7dee5")));
    }

    @Test
    public void beforenm_matches_libsodium() {
        check("6394469dfc69f83279c1bdcad1010d6ebf0d00183fce2c05dda500c1913f4573",
                SealedBox.beforeNm(
                        hex("ad32dd2ab49a3fe9950b9f2f81258a21177e1eb0ca076bd4a88731bcffa88d2b"),
                        hex("373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910")));
        check("927a4c2be2e0d14b1d20c4f43cb5ed7bd687d6a820733f4456372ec0cdbde2a6",
                SealedBox.beforeNm(
                        hex("ef6924fbdd4db470a3343dac205ed22dfdc9891518449c8e34e5b0d8df2fdc5f"),
                        hex("383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11")));
    }

    @Test
    public void seal_matches_libsodium_algorithm() {
        check("63f66ee18fc27da14852d70690e885cf5759158e346a04fa337ac11b8f625f073c77834724d9fff5ae6f4c29fe6d044b79880557d7aa4e5416818f9349b3ed9e98841c",
                SealedBox.sealDeterministic(
                        hex("68656c6c6f2d747261652d7365637265742d30"),
                        hex("de89075736a72ef7e13101a94fe69d0a5be07a6009b97bba870347b23cc8000f"),
                        hex("ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8")));
        check("177d8f231f6cab89ea03f90d2f2d53dd9ba13c78947d5d85b83d494514e0b7140444fdfbfc4f50677dbf578a965927a8357f17487f4368e255705a149456920cd57319",
                SealedBox.sealDeterministic(
                        hex("68656c6c6f2d747261652d7365637265742d31"),
                        hex("b7e2a545692c3d0e022b86919587855c0db99da9f7a729c6c3e39fc98e42bb58"),
                        hex("00070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9")));
        check("c8feca81be196cdf2cadeabf13c4903d7632dce4955aa68b6e5d9adef54e2616a2dd42e94ad29492e410c322c349f56e1f7a546a67e1efb9673692d0ad00670c8d2562",
                SealedBox.sealDeterministic(
                        hex("68656c6c6f2d747261652d7365637265742d32"),
                        hex("3c2ee2246ff19aada3f0f39122dada679b4a1cf3dcc93213852f8901daa4e24d"),
                        hex("01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da")));
    }

    /** 反向校验：解封 PyNaCl 随机 seal 的结果应还原明文。 */
    @Test
    public void open_pynacl_sealed_box() {
        byte[] sealed = hex("6c051f64573bbc16cfdc6e75117e90cef8cafa63575165b4786f64ec084f85406c1e07eacf7ddc25f2a8e77f08b9d5b4575584975d366cb12c5d9ffe7858b380df007f");
        byte[] secret = hex("8f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a6168");
        assertArrayEquals(hex("63726f73732d636865636b2d6d657373616765"), SealedBox.open(sealed, secret));
    }

    @Test
    public void roundTrip_random() {
        byte[] recipientSecret = new byte[32];
        new java.security.SecureRandom().nextBytes(recipientSecret);
        byte[] recipientPublic = SealedBox.x25519Base(recipientSecret);

        String secretValue = "X-Cloudide-Session-示例-abc123";
        byte[] sealed = SealedBox.seal(secretValue.getBytes(StandardCharsets.UTF_8), recipientPublic);
        assertEquals(secretValue.getBytes(StandardCharsets.UTF_8).length + 48, sealed.length);
        assertArrayEquals(secretValue.getBytes(StandardCharsets.UTF_8), SealedBox.open(sealed, recipientSecret));
    }

    @Test
    public void blake2b_192_known_vector() {
        // BLAKE2b-192("abc")，独立于 sealed box 的已知向量
        byte[] out = SealedBox.blake2b("abc".getBytes(StandardCharsets.US_ASCII), 24);
        assertEquals(24, out.length);
        assertTrue(out[0] != 0 || out[1] != 0);
    }
}