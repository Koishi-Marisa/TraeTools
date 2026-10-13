package com.traetools.android.secret

import android.util.Base64
import org.json.JSONObject

/** 从登录 token（JWT）中解析账号唯一标识，仅用于界面展示。 */
object JwtUtils {
    fun parseUid(jwt: String?): String? {
        if (jwt.isNullOrEmpty()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = parts[1].replace('-', '+').replace('_', '/')
            val padded = when (payload.length % 4) {
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            val json = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(json).optJSONObject("data")?.optString("id")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}