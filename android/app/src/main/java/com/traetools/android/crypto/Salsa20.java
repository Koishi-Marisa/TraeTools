package com.traetools.android.crypto;

/**
 * Salsa20 / HSalsa20 / XSalsa20 核心（纯 JVM 实现，仅用于 libsodium sealed box 所需的运算）。
 *
 * 与 libsodium 的布局保持一致：
 *   状态 16 个字：0、5、10、15 为常量 "expand 32-byte k"，1-4 与 11-14 为密钥，其余为 nonce/计数器。
 *   HSalsa20 输出取「加法前」的 (0,5,10,15,6,7,8,9) 八个字，用于从 X25519 共享密钥派生 box 密钥。
 */
final class Salsa20 {

    private Salsa20() {
    }

    private static final byte[] SIGMA = "expand 32-byte k".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int rotl(int x, int n) {
        return (x << n) | (x >>> (32 - n));
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[b] ^= rotl(x[a] + x[d], 7);
        x[c] ^= rotl(x[b] + x[a], 9);
        x[d] ^= rotl(x[c] + x[b], 13);
        x[a] ^= rotl(x[d] + x[c], 18);
    }

    /** Salsa20 的 20 轮（10 次双轮）核心变换，原地作用于 x。 */
    private static void rounds(int[] x) {
        for (int i = 0; i < 10; i++) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 5, 9, 13, 1);
            quarterRound(x, 10, 14, 2, 6);
            quarterRound(x, 15, 3, 7, 11);
            quarterRound(x, 0, 1, 2, 3);
            quarterRound(x, 5, 6, 7, 4);
            quarterRound(x, 10, 11, 8, 9);
            quarterRound(x, 15, 12, 13, 14);
        }
    }

    /**
     * HSalsa20：key 32 字节、nonce 16 字节，输出 32 字节子密钥（无加法、无计数器）。
     */
    static byte[] hsalsa20(byte[] key, byte[] nonce, int nonceOff) {
        int[] x = new int[16];
        x[0] = le32(SIGMA, 0);
        x[5] = le32(SIGMA, 4);
        x[10] = le32(SIGMA, 8);
        x[15] = le32(SIGMA, 12);
        for (int i = 0; i < 4; i++) {
            x[1 + i] = le32(key, 4 * i);
            x[6 + i] = le32(nonce, nonceOff + 4 * i);
            x[11 + i] = le32(key, 16 + 4 * i);
        }
        rounds(x);
        byte[] out = new byte[32];
        int[] pick = {0, 5, 10, 15, 6, 7, 8, 9};
        for (int i = 0; i < 8; i++) {
            int v = x[pick[i]];
            out[4 * i] = (byte) v;
            out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16);
            out[4 * i + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    /** 生成一个 Salsa20 密钥流块（64 字节），nonce 为 8 字节，blockIndex 为 64 位计数器。 */
    private static void salsa20Block(int[] out, byte[] key, byte[] nonce8, int nonceOff, long blockIndex) {
        int[] x = new int[16];
        x[0] = le32(SIGMA, 0);
        x[5] = le32(SIGMA, 4);
        x[10] = le32(SIGMA, 8);
        x[15] = le32(SIGMA, 12);
        for (int i = 0; i < 4; i++) {
            x[1 + i] = le32(key, 4 * i);
            x[11 + i] = le32(key, 16 + 4 * i);
        }
        x[6] = le32(nonce8, nonceOff);
        x[7] = le32(nonce8, nonceOff + 4);
        x[8] = (int) blockIndex;
        x[9] = (int) (blockIndex >>> 32);
        int[] z = x.clone();
        rounds(z);
        for (int i = 0; i < 16; i++) {
            out[i] = z[i] + x[i];
        }
    }

    /**
     * XSalsa20 密钥流：nonce 24 字节，先用 HSalsa20 由前 16 字节派生子密钥，
     * 再用后 8 字节作为 Salsa20 nonce 生成 {@code len} 字节密钥流。
     */
    static byte[] xsalsa20Keystream(byte[] key, byte[] nonce24, int len) {
        byte[] subkey = hsalsa20(key, nonce24, 0);
        byte[] out = new byte[len];
        int[] block = new int[16];
        int written = 0;
        long counter = 0;
        while (written < len) {
            salsa20Block(block, subkey, nonce24, 16, counter);
            for (int i = 0; i < 16 && written < len; i++) {
                int v = block[i];
                for (int b = 0; b < 4 && written < len; b++) {
                    out[written++] = (byte) (v >>> (8 * b));
                }
            }
            counter++;
        }
        return out;
    }
}