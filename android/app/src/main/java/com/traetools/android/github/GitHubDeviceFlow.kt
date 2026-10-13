package com.traetools.android.github

import com.traetools.android.net.Http
import org.json.JSONObject

/** 携带可读中文信息的 GitHub 操作异常。 */
class GitHubException(message: String) : Exception(message)

/** 设备码授权申请结果。 */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Int
)

/**
 * GitHub OAuth 设备码授权（Device Flow）。
 * 与桌面版 TraeTools 复用同一个 OAuth App（Client ID），无需 client secret。
 * 申请的 scope：repo + workflow —— 写仓库 Secrets 与触发 workflow 所需。
 */
object GitHubDeviceFlow {

    private const val CLIENT_ID = "Ov23lix0Kb9ldJHrOpKv"
    private const val SCOPE = "repo workflow"

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val FORM = "application/x-www-form-urlencoded"
    private const val ACCEPT_JSON = "application/json"

    fun requestCode(): DeviceCode {
        val resp = Http.request(
            method = "POST",
            url = DEVICE_CODE_URL,
            headers = mapOf("Accept" to ACCEPT_JSON),
            body = Http.form(mapOf("client_id" to CLIENT_ID, "scope" to SCOPE)),
            contentType = FORM
        )
        if (!resp.isSuccess) {
            throw GitHubException("申请设备码失败：HTTP ${resp.code} ${resp.body.take(200)}")
        }
        val o = resp.json()
        return DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            verificationUri = o.optString("verification_uri", "https://github.com/login/device"),
            interval = o.optInt("interval", 5)
        )
    }

    sealed class Poll {
        /** 用户尚未在网页完成授权，继续轮询。 */
        object Pending : Poll()

        /** 已拿到 access_token。 */
        data class Success(val token: String) : Poll()

        /** 授权被拒绝或设备码失效。 */
        data class Failure(val reason: String) : Poll()
    }

    fun poll(deviceCode: String): Poll {
        val resp = Http.request(
            method = "POST",
            url = ACCESS_TOKEN_URL,
            headers = mapOf("Accept" to ACCEPT_JSON),
            body = Http.form(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                )
            ),
            contentType = FORM
        )
        if (!resp.isSuccess) return Poll.Failure("HTTP ${resp.code}")

        val o = runCatching { JSONObject(resp.body) }.getOrElse { return Poll.Failure("响应解析失败") }
        o.optString("access_token").takeIf { it.isNotEmpty() }?.let { return Poll.Success(it) }

        return when (o.optString("error")) {
            "authorization_pending", "slow_down" -> Poll.Pending
            "expired_token" -> Poll.Failure("设备码已过期，请重新授权")
            "access_denied" -> Poll.Failure("授权被用户拒绝")
            else -> Poll.Failure("授权失败：${o.optString("error", "unknown")}")
        }
    }
}