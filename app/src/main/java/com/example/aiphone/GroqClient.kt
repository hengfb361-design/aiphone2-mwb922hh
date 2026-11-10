package com.example.aiphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The Groq key lives in the phone, not in the APK source. */
object Prefs {
    private const val FILE = "ai_phone"
    private const val KEY = "groq_key"

    fun key(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun setKey(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}

/** Groq chat completions with model failover: 429 (rate limit), 413, 404
    (retired model) or 5xx moves on to the next model instead of failing. */
object GroqClient {
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODELS_URL = "https://api.groq.com/openai/v1/models"
    private val PREFERRED = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")

    data class Reply(val ok: Boolean, val text: String, val model: String)

    private class Raw(val code: Int, val body: String)

    fun chat(context: Context, system: String, user: String, log: (String) -> Unit): Reply {
        val key = Prefs.key(context)
        if (key.isEmpty()) {
            return Reply(false, "គ្មាន Groq API key — បញ្ចូលក្នុង app សិន (console.groq.com)", "")
        }
        val models = listModels(key)
        var last = "មិនអាចហៅ Groq បាន"
        for (index in models.indices) {
            val model = models[index]
            val raw = call(key, model, system, user)
            if (raw.code == 200) {
                val text = content(raw.body)
                if (text.isNotEmpty()) return Reply(true, text, model)
            }
            last = errorOf(raw)
            val next = models.getOrNull(index + 1)
            if (!retryable(raw.code) || next == null) break
            log("🔴 " + model + " (" + raw.code + ") → ប្តូរ " + next)
        }
        return Reply(false, last, "")
    }

    private fun retryable(code: Int): Boolean =
        code == 429 || code == 413 || code == 404 || code == 500 || code == 503

    private fun listModels(key: String): List<String> {
        val raw = request(MODELS_URL, key, null)
        if (raw.code != 200) return PREFERRED
        val ids = mutableListOf<String>()
        try {
            val data = JSONObject(raw.body).optJSONArray("data") ?: return PREFERRED
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: continue
                val low = id.lowercase()
                if (low.contains("whisper") || low.contains("tts") || low.contains("guard")) continue
                if (low.contains("embed") || low.contains("orpheus")) continue
                ids.add(id)
            }
        } catch (e: Exception) {
            return PREFERRED
        }
        if (ids.isEmpty()) return PREFERRED
        return ids.sortedWith(compareBy({ rank(it) }, { it })).take(5)
    }

    private fun rank(id: String): Int {
        val at = PREFERRED.indexOf(id)
        return if (at < 0) PREFERRED.size else at
    }

    private fun call(key: String, model: String, system: String, user: String): Raw {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 700)
        return request(CHAT_URL, key, payload.toString())
    }

    private fun content(body: String): String = try {
        JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun errorOf(raw: Raw): String = try {
        JSONObject(raw.body).optJSONObject("error")?.optString("message") ?: ("HTTP " + raw.code)
    } catch (e: Exception) {
        "HTTP " + raw.code + " " + raw.body.take(120)
    }

    private fun request(url: String, key: String, payload: String?): Raw {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.requestMethod = if (payload == null) "GET" else "POST"
                conn.setRequestProperty("Authorization", "Bearer " + key)
                conn.setRequestProperty("Content-Type", "application/json")
                if (payload != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Raw(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Raw(0, e.message ?: "ភ្ជាប់មិនបាន")
        }
    }
}
