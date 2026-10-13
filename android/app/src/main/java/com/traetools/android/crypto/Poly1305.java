package com.traetools.android.crypto;

import java.math.BigInteger;

/**
 * Poly1305 一次性认证器（纯 JVM，BigInteger 实现；单次仅处理 GitHub secret 这类短报文，性能非关键）。
 * 与 NaCl/libsodium 的 crypto_secretbox 所用构造一致。
 */
final class Poly1305 {

    private Poly1305() {
    }

    private static final BigInteger P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    /** 把 little-endian 字节片段转成非负 BigInteger。 */
    private static BigInteger leToBigInt(byte[] b, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) {
            be[len - 1 - i] = b[off + i];
        }
        return new BigInteger(1, be);
    }

    /** 把 BigInteger 低 128 位按 little-endian 写入 16 字节。 */
    private static byte[] bigIntToLe16(BigInteger v) {
        BigInteger x = v.and(MASK128);
        byte[] be = x.toByteArray();
        byte[] out = new byte[16];
        for (int i = 0; i < be.length && i < 16; i++) {
            // be 为大端、可能带前导 0 或补符号位，从末尾往前取
            int idx = be.length - 1 - i;
            out[i] = be[idx];
        }
        return out;
    }

    /**
     * 计算 Poly1305 标签。key 为 32 字节（前 16 字节为 r，后 16 字节为 s）。
     */
    static byte[] auth(byte[] msg, byte[] key) {
        byte[] rBytes = new byte[16];
        System.arraycopy(key, 0, rBytes, 0, 16);
        // r 的 clamp（RFC 8439 §2.5）
        rBytes[3] &= 15;
        rBytes[7] &= 15;
        rBytes[11] &= 15;
        rBytes[15] &= 15;
        rBytes[4] &= (byte) 252;
        rBytes[8] &= (byte) 252;
        rBytes[12] &= (byte) 252;
        BigInteger r = leToBigInt(rBytes, 0, 16);
        BigInteger s = leToBigInt(key, 16, 16);

        BigInteger acc = BigInteger.ZERO;
        int off = 0;
        while (off < msg.length) {
            int len = Math.min(16, msg.length - off);
            byte[] block = new byte[len + 1];
            System.arraycopy(msg, off, block, 0, len);
            block[len] = 1; // 追加 1 位（0x01 在最高有效端，LE 下即紧随数据之后）
            BigInteger n = leToBigInt(block, 0, block.length);
            acc = acc.add(n).multiply(r).mod(P);
            off += len;
        }
        acc = acc.add(s);
        return bigIntToLe16(acc);
    }
}