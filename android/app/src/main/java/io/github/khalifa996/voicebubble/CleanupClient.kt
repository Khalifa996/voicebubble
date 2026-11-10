package io.github.khalifa996.voicebubble

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends the raw transcript to the Cloudflare Worker, which asks Claude to tidy it up.
 * Never throws and never loses words: on any problem the result carries the raw text.
 */
object CleanupClient {

    data class Result(val text: String, val cleaned: Boolean, val error: String? = null)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 12_000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun clean(prefs: Prefs, raw: String, appPackage: String?, context: String, callback: (Result) -> Unit) {
        val url = prefs.workerUrl
        val token = prefs.appToken
        val vocabulary = prefs.vocabularyList
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("text", raw)
                    .put("app", appPackage.orEmpty())
                    .put("vocabulary", JSONArray(vocabulary))
                    .put("context", context)
                    .put("habits", JSONArray(prefs.habitsFor(appPackage)))
                val (code, response) = post("$url/clean", token, body.toString())
                if (code == 200) {
                    val cleaned = JSONObject(response).optString("text", "").trim()
                    // An empty reply is fine for "um" on its own, not for a real sentence.
                    if (cleaned.isEmpty() && wordCount(raw) > 3) Result(raw, false, "empty reply")
                    else Result(cleaned, true)
                } else {
                    Result(raw, false, describe(code, response))
                }
            } catch (e: SocketTimeoutException) {
                Result(raw, false, "timed out")
            } catch (e: IOException) {
                Result(raw, false, "no connection")
            } catch (e: Exception) {
                Result(raw, false, e.javaClass.simpleName)
            }
            main.post { callback(result) }
        }
    }

    /** Round trip with a sample sentence, for the "Test connection" button. */
    fun test(prefs: Prefs, callback: (String) -> Unit) {
        val url = prefs.workerUrl
        val token = prefs.appToken
        executor.execute {
            val message = try {
                val sample = "um so this is a quick test of the the voice bubble scratch that the voice bubble app full stop"
                val body = JSONObject().put("text", sample).put("app", "")
                val started = System.nanoTime()
                val (code, response) = post("$url/clean", token, body.toString())
                val ms = (System.nanoTime() - started) / 1_000_000
                if (code == 200) {
                    val text = JSONObject(response).optString("text", "")
                    "✓ Working in $ms ms\n\nSent: \"$sample\"\n\nGot back: \"$text\""
                } else {
                    "✗ ${describe(code, response)}"
                }
            } catch (e: SocketTimeoutException) {
                "✗ Timed out. Check the Worker URL."
            } catch (e: IOException) {
                "✗ Couldn't reach the Worker: ${e.message}"
            } catch (e: Exception) {
                "✗ ${e.javaClass.simpleName}: ${e.message}"
            }
            main.post { callback(message) }
        }
    }

    private fun post(url: String, token: String, json: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    fun describe(code: Int, response: String): String {
        val error = try {
            JSONObject(response).optString("error", "")
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 401 -> "wrong app token"
            code == 404 -> "wrong Worker URL"
            error == "worker_not_configured" -> "Worker secrets missing"
            error == "speech_not_set_up" -> "Worker needs redeploying for cloud transcription"
            error.isNotEmpty() -> error.replace('_', ' ')
            else -> "HTTP $code"
        }
    }

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
