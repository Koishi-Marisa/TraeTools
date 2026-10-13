package com.traetools.android.github

import com.traetools.android.net.Http
import com.traetools.android.net.HttpResponse
import com.traetools.android.secret.SecretEncryptor
import org.json.JSONObject

/** GitHub REST API 封装：读写仓库 Actions Secrets、查找/触发 workflow。 */
class GitHubApi(private val token: String) {

    private fun api(method: String, path: String, body: JSONObject? = null): HttpResponse =
        Http.request(
            method = method,
            url = "https://api.github.com$path",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "TraeTools-Android"
            ),
            body = body?.toString(),
            contentType = "application/json"
        )

    /** 取当前授权用户登录名；token 失效会抛异常。 */
    fun getLogin(): String {
        val resp = api("GET", "/user")
        if (!resp.isSuccess) {
            throw GitHubException("获取 GitHub 用户信息失败：HTTP ${resp.code}（授权可能已失效，请重新授权）")
        }
        return resp.json().getString("login")
    }

    fun repoExists(owner: String, repo: String): Boolean = api("GET", "/repos/$owner/$repo").isSuccess

    /** 用仓库公钥加密后写入一个 Actions secret。 */
    fun putSecret(owner: String, repo: String, name: String, value: String) {
        val pkResp = api("GET", "/repos/$owner/$repo/actions/secrets/public-key")
        if (!pkResp.isSuccess) {
            throw GitHubException("获取仓库公钥失败：HTTP ${pkResp.code}（请确认授权含 Secrets 权限）")
        }
        val pk = pkResp.json()
        val encrypted = SecretEncryptor.encrypt(value, pk.getString("key"))
        val body = JSONObject().apply {
            put("encrypted_value", encrypted)
            put("key_id", pk.getString("key_id"))
        }
        val resp = api("PUT", "/repos/$owner/$repo/actions/secrets/$name", body)
        if (!resp.isSuccess) {
            throw GitHubException("写入 secret $name 失败：HTTP ${resp.code} ${resp.body.take(160)}")
        }
    }

    /** 删除 secret；不存在（404）视为成功。 */
    fun deleteSecret(owner: String, repo: String, name: String) {
        val resp = api("DELETE", "/repos/$owner/$repo/actions/secrets/$name")
        if (!resp.isSuccess && resp.code != 404) {
            throw GitHubException("清理 secret $name 失败：HTTP ${resp.code}")
        }
    }

    fun findWorkflowId(owner: String, repo: String, workflowPath: String): Long? {
        val resp = api("GET", "/repos/$owner/$repo/actions/workflows")
        if (!resp.isSuccess) return null
        val arr = resp.json().optJSONArray("workflows") ?: return null
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            if (w.optString("path") == workflowPath) return w.optLong("id")
        }
        return null
    }

    /** 手动触发一次签到 workflow。 */
    fun dispatchWorkflow(owner: String, repo: String, workflowPath: String, ref: String = "main") {
        val id = findWorkflowId(owner, repo, workflowPath)
            ?: throw GitHubException("未找到 workflow：$workflowPath（请确认仓库名与是否已启用 Actions）")
        val resp = api(
            "POST", "/repos/$owner/$repo/actions/workflows/$id/dispatches",
            JSONObject().apply { put("ref", ref) }
        )
        if (!resp.isSuccess) {
            throw GitHubException("触发 workflow 失败：HTTP ${resp.code} ${resp.body.take(160)}")
        }
    }
}