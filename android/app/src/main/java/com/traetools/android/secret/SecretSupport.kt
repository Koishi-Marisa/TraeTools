package com.traetools.android.secret

import android.util.Base64
import com.traetools.android.crypto.SealedBox

/** Secret 名与编号的映射，必须与 checkin.py 的读取规则保持一致。 */
object SecretNaming {
    const val WORKFLOW_PATH = ".github/workflows/checkin.yml"

    /** 第 index 个（从 1 起）账号的 session secret 名：1 → TRAE_SESSION，N → TRAE_SESSION_N。 */
    fun sessionName(index: Int): String =
        if (index <= 1) "TRAE_SESSION" else "TRAE_SESSION_$index"

    /** 第 index 个（从 1 起）账号的 device id secret 名：1 → TRAE_DEVICE_ID，N → TRAE_DEVICE_ID_N。 */
    fun deviceName(index: Int): String =
        if (index <= 1) "TRAE_DEVICE_ID" else "TRAE_DEVICE_ID_$index"
}

/** GitHub Actions secret 加密：libsodium crypto_box_seal。 */
object SecretEncryptor {
    /**
     * 用仓库公开密钥（base64 编码的 32 字节 X25519 公钥）加密明文，
     * 返回 base64 密文，可直接作为 PUT secret 的 encrypted_value。
     */
    fun encrypt(plaintext: String, publicKeyBase64: String): String {
        val publicKey = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val sealed = SealedBox.seal(plaintext.toByteArray(Charsets.UTF_8), publicKey)
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }
}