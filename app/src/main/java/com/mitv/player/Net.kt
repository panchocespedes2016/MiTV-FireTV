package com.mitv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", "MiTV/1.0 (FireTV)").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    fun getText(url: String): String = String(getBytes(url), Charsets.UTF_8)

    fun getJsonArray(url: String): JSONArray {
        val txt = getText(url).trim()
        if (txt.startsWith("[")) return JSONArray(txt)
        // Some panels return an object for empty lists; treat as empty.
        return JSONArray()
    }

    fun getJsonObject(url: String): JSONObject = JSONObject(getText(url))

    fun maybeGunzip(bytes: ByteArray): ByteArray {
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
        return bytes
    }

    fun esc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Small JSON helpers tolerant of string/number/bool Xtream fields. */
fun JSONObject.str(name: String): String {
    if (!has(name) || isNull(name)) return ""
    val v = opt(name)
    return when (v) {
        is String -> v
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }
}

fun JSONObject.lng(name: String): Long {
    if (!has(name) || isNull(name)) return 0
    val v = opt(name)
    return when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0
        else -> 0
    }
}
