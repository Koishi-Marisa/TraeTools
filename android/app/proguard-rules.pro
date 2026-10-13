# 保留 BouncyCastle 的 X25519 / BLAKE2b 实现（反射/JCE 无关，仅直接调用静态方法）
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.crypto.digests.Blake2bDigest { *; }