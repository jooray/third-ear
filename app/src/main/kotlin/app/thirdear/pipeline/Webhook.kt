package app.thirdear.pipeline

import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Posts a recording as multipart/form-data: `metadata` (JSON), `transcript` (text, when there is
 * one) and `audio` (unless only text is sent). Works with n8n, Home Assistant webhooks, a
 * self-hosted agent; any 2xx counts as delivered. Same format as I Hear You. https only (ActionPlan.isHttpsUrl).
 */
object Webhook {
    private const val CONNECT_TIMEOUT_MS = 20_000
    // the receiver may run the transcript through an agent before it answers
    private const val READ_TIMEOUT_MS = 120_000
    private const val CHUNK_BYTES = 64 * 1024
    private val HTTP_SUCCESS = 200..299


    fun post(url: String, auth: String?, metadata: JSONObject, transcript: String?, audio: File?, audioName: String, audioMime: String) {
        val boundary = "thirdear-" + System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setChunkedStreamingMode(CHUNK_BYTES)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("User-Agent", "ThirdEar")
            if (!auth.isNullOrBlank()) conn.setRequestProperty("Authorization", auth.trim())
            DataOutputStream(conn.outputStream).use { out ->
                fun part(name: String, type: String, fileName: String? = null) {
                    out.writeBytes("--$boundary\r\n")
                    val disp = "form-data; name=\"$name\"" + (fileName?.let { "; filename=\"${it.replace("\"", "")}\"" } ?: "")
                    out.writeBytes("Content-Disposition: $disp\r\n")
                    out.writeBytes("Content-Type: $type\r\n\r\n")
                }
                part("metadata", "application/json")
                out.write(metadata.toString().toByteArray())
                out.writeBytes("\r\n")
                if (transcript != null) {
                    part("transcript", "text/plain; charset=utf-8")
                    out.write(transcript.toByteArray())
                    out.writeBytes("\r\n")
                }
                if (audio != null) {
                    part("audio", audioMime, audioName)
                    audio.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code !in HTTP_SUCCESS) error("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
