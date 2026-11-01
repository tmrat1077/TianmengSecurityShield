package com.tianmeng.security.manager

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 意见反馈上报：对接 https://yunju.99kpk.top/API/fk_tj.php
 * 参数 appid / key / user / content，返回 JSON { code, msg }。
 */
object FeedbackManager {

    private const val TAG = "FeedbackManager"
    private const val ENDPOINT = "https://yunju.99kpk.top/API/fk_tj.php"
    private const val APP_ID = "2954"
    private const val APP_KEY = "1793440105"

    data class Result(val code: Int, val msg: String) {
        val isSuccess: Boolean get() = code == 1
    }

    /**
     * 提交反馈：优先 POST，失败时回退 GET（接口两种方式都支持）。
     */
    fun submit(user: String, content: String): Result {
        val body = encode(
            mapOf(
                "appid" to APP_ID,
                "key" to APP_KEY,
                "user" to user,
                "content" to content
            )
        )
        val raw = try {
            request("POST", body)
        } catch (e: Throwable) {
            Log.w(TAG, "POST failed, fallback to GET: ${e.message}")
            try {
                request("GET", body)
            } catch (e2: Throwable) {
                Log.e(TAG, "Feedback request failed", e2)
                return Result(-1, "网络连接失败，请检查网络后重试")
            }
        }
        return parse(raw)
    }

    private fun encode(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun request(method: String, body: String): String {
        val url = if (method == "GET") "$ENDPOINT?$body" else ENDPOINT
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val httpCode = conn.responseCode
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Log.d(TAG, "$method http=$httpCode body=$raw")
            if (httpCode !in 200..299 && raw.isBlank()) throw IOException("HTTP $httpCode")
            raw
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): Result {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) {
            return Result(-1, "服务器返回异常：${text.take(80)}")
        }
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            val code = obj.optInt("code", -1)
            val msg = obj.optString("msg", "").ifBlank {
                if (code == 1) "反馈成功" else "反馈失败"
            }
            Result(code, msg)
        } catch (e: Throwable) {
            Result(-1, "服务器返回解析失败：${text.take(80)}")
        }
    }
}
