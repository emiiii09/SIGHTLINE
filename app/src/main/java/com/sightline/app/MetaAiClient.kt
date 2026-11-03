// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI layer of Sightline, built on Meta's Model API (OpenAI-compatible).
 *
 * Endpoint: https://api.meta.ai/v1/chat/completions
 * Model: muse-spark-1.3 — multimodal reasoning, used here to turn raw
 * findings into contextual fixes with corrected code snippets.
 * Key: provided at runtime by each user from Settings (memory only,
 * never persisted, never shipped in the build).
 *
 * JSON is built and parsed by hand on purpose: org.json ships as a stub
 * inside android.jar, so its methods throw in local unit tests. Keeping this
 * class dependency-free (OkHttp aside) lets the whole suite run on the JVM.
 */
class MetaAiClient(
    private val apiKey: String,
    private val model: String = MODEL,
) {
    companion object {
        const val BASE_URL = "https://api.meta.ai/v1/chat/completions"
        const val MODELS_URL = "https://api.meta.ai/v1/models"
        const val MODEL = "muse-spark-1.3-contributor"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Maps a key-validation failure to a message key: any 4xx from Meta
         * means the key was rejected; anything else is a connection problem.
         */
        fun validationFailureKey(msg: String): String {
            val code = Regex("Meta API error (\\d+)").find(msg)
                ?.groupValues?.get(1)?.toIntOrNull()
            return if (code != null && code in 400..499) "t_keyInvalid" else "aiNetFail"
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun hasKey(): Boolean = apiKey.isNotBlank()

    /** Builds the exact JSON payload sent to the Meta Model API. */
    fun buildPayload(issue: Issue, pageContext: String, lang: String = "en"): String {
        val system = "You are Sightline, an expert web-accessibility assistant. " +
            "Explain the issue in one short sentence for a developer, then give " +
            "the minimal corrected HTML snippet. Keep the whole answer under 120 words." +
            if (lang == "es") " Reply in Spanish (voseo)." else " Reply in English."
        val user = "Issue type: ${issue.type}\n" +
            "Finding: ${issue.description}\n" +
            "Element: ${issue.element}\n" +
            "Page context (trimmed): ${pageContext.take(1500)}"
        return "{\"model\":${q(model)}," +
            "\"messages\":[" +
            "{\"role\":\"system\",\"content\":${q(system)}}," +
            "{\"role\":\"user\",\"content\":${q(user)}}]," +
            "\"temperature\":0.2," +
            // Spark 1.3 reasons before answering (hundreds of tokens, more
            // for long prompts). A tight budget returns content:null, so
            // leave generous headroom above the ~120-word answer.
            "\"max_tokens\":4000}"
    }

    /**
     * Real key check against the live API: 200 means the key is accepted,
     * 401/403 means it is missing, invalid or lacks access. This performs a
     * real request — validity is never inferred from the key format.
     * Callback runs on a background thread.
     */
    fun validateKey(key: String, callback: (Result<Unit>) -> Unit) {
        if (key.isBlank()) {
            callback(Result.failure(IllegalStateException("empty key")))
            return
        }
        val req = Request.Builder()
            .url(MODELS_URL)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.code == 200) callback(Result.success(Unit))
                    else callback(Result.failure(IOException("Meta API error ${it.code}")))
                }
            }
        })
    }

    /** Builds the whole-page assessment payload sent to the Meta Model API. */
    fun buildAnalysisPayload(url: String, issues: List<Issue>, lang: String = "en"): String {
        val shown = issues.take(12).mapIndexed { i, issue ->
            "${i + 1}. [${issue.severity}] ${issue.type}: ${issue.element.take(160)}"
        }.joinToString("\n")
        val system = "You are Sightline, an expert web-accessibility assistant. " +
            "Write a 2-sentence verdict in plain words for a non-expert, then list " +
            "the top 3 findings to fix first as 'N. reason'. Keep it under 120 words." +
            if (lang == "es") " Reply in Spanish (voseo)." else " Reply in English."
        val user = "Audited page: $url\nFindings (${issues.size} total):\n$shown"
        return "{\"model\":${q(model)}," +
            "\"messages\":[" +
            "{\"role\":\"system\",\"content\":${q(system)}}," +
            "{\"role\":\"user\",\"content\":${q(user)}}]," +
            "\"temperature\":0.2," +
            // Headroom for the model's reasoning (see buildPayload).
            "\"max_tokens\":4000}"
    }

    /**
     * Asks Muse Spark for a whole-page verdict. Callback runs on a background
     * thread — the Activity must post back to the UI thread.
     */
    fun analyzePage(
        url: String,
        issues: List<Issue>,
        lang: String = "en",
        callback: (Result<String>) -> Unit,
    ) {
        if (!hasKey()) {
            callback(Result.failure(IllegalStateException("No API key configured for this session")))
            return
        }
        postJson(buildAnalysisPayload(url, issues, lang), callback)
    }

    /**
     * Asks Muse Spark for a contextual fix. Callback runs on a background
     * thread — the Activity must post back to the UI thread.
     */
    fun suggestFix(
        issue: Issue,
        pageContext: String,
        lang: String = "en",
        callback: (Result<String>) -> Unit,
    ) {
        if (!hasKey()) {
            callback(Result.failure(IllegalStateException("No API key configured for this session")))
            return
        }
        val body = buildPayload(issue, pageContext, lang).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Meta API error ${it.code}")))
                        return
                    }
                    val text = it.body?.string().orEmpty()
                    callback(Result.success(extractContent(text)))
                }
            }
        })
    }

    /** Posts a pre-built JSON payload and extracts the reply content. */
    private fun postJson(payload: String, callback: (Result<String>) -> Unit) {
        val body = payload.toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Meta API error ${it.code}")))
                        return
                    }
                    val text = it.body?.string().orEmpty()
                    callback(Result.success(extractContent(text)))
                }
            }
        })
    }

    /**
     * Extracts choices[0].message.content from a chat-completions response.
     * Returns "" for null content or unparseable bodies.
     */
    fun extractContent(responseJson: String): String {
        val m = Regex("\"content\"\\s*:\\s*(null|\"((?:[^\"\\\\]|\\\\.)*)\")")
            .find(responseJson) ?: return ""
        if (m.groupValues[1] == "null") return ""
        return unescape(m.groupValues[2])
    }

    // -- minimal JSON helpers (no org.json: unmocked in unit tests) ---------

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim()
    }
}
