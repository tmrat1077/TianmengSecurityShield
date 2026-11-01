package com.tianmeng.security.manager

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程维护公告：读取 echouser.php?file=maintain，返回维护提示内容。
 */
object MaintenanceManager {

    private const val ADMIN = "3697484683"
    private const val DEFAULT_MESSAGE = "系统维护中，部分功能可能暂时不可用，请稍后再试。"

    private val ENDPOINTS = listOf(
        "https://yunju.99kpk.top/API/echouser.php",
        "https://yunju.99kpk.top/echouser.php"
    )

    data class Notice(val message: String)

    /** 返回维护公告；未开启维护或接口不可用时返回 null。 */
    fun check(): Notice? {
        for (endpoint in ENDPOINTS) {
            val response = try {
                fetch("$endpoint?file=maintain&admin=$ADMIN")
            } catch (e: Throwable) {
                continue
            }
            if (response.code !in 200..299) continue
            return parse(response.body)
        }
        return null
    }

    private data class Response(val code: Int, val body: String)

    private fun fetch(url: String): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "TianmengShield")
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): Notice? {
        val body = raw.trim()
        if (body.isEmpty()) return null
        if (body.startsWith("{") || body.startsWith("[")) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val message = json.optString("msg").ifBlank {
                json.optString("message").ifBlank { DEFAULT_MESSAGE }
            }
            val enabled = json.optBoolean("maintain", false) ||
                json.optBoolean("maintenance", false) ||
                json.optInt("status", 0) == 1 ||
                json.optInt("code", 0) == 1
            return if (enabled) Notice(message) else null
        }
        val lower = body.lowercase()
        val enabled = lower == "1" || lower == "on" || lower == "true" ||
            lower.contains("维护") || lower.contains("maintain")
        if (!enabled) return null
        return Notice(if (body.length <= 200) body else DEFAULT_MESSAGE)
    }
}
