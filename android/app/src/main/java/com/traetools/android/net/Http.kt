package com.traetools.android.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 简易 HTTP 响应封装。 */
data class HttpResponse(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
    fun json(): JSONObject = JSONObject(body)
}

/**
 * 基于 HttpURLConnection 的最小 HTTP 客户端。
 * 非 2xx 不抛异常，而是把状态码与响应体一起返回，交由上层判断并给出可读提示。
 */
object Http {

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readAll(it) } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 将 map 编码为 application/x-www-form-urlencoded 请求体。 */
    fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    private fun readAll(input: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = reader.readLine()
            }
        }
        return sb.toString()
    }
}