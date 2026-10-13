package com.traetools.android.data

import org.json.JSONArray
import org.json.JSONObject

/** 一个 Trae 账号的本地凭证。 */
data class Account(
    var session: String = "",
    var deviceId: String = "",
    var uid: String? = null,
    var note: String? = null
) {
    val hasSession: Boolean get() = session.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("session", session)
        put("deviceId", deviceId)
        put("uid", uid ?: JSONObject.NULL)
        put("note", note ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            session = o.optString("session", ""),
            deviceId = o.optString("deviceId", ""),
            uid = if (o.isNull("uid")) null else o.optString("uid"),
            note = if (o.isNull("note")) null else o.optString("note")
        )

        fun listToJson(list: List<Account>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String?): MutableList<Account> {
            val out = mutableListOf<Account>()
            if (json.isNullOrEmpty()) return out
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    out.add(fromJson(arr.getJSONObject(i)))
                }
            } catch (_: Exception) {
                // 历史数据结构不兼容时按空列表处理，避免启动崩溃
            }
            return out
        }
    }
}