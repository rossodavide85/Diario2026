package it.davide.diario

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors the local diary against the personal "La Corsa" server, so the same
 * diary can be read and edited from the phone, the website, and Claude:
 * - push(): after every local save, best-effort fire-and-forget upload.
 * - pull(): on app launch, best-effort download to pick up edits made
 *   elsewhere (e.g. the website's "Alcol" tab) since the last time the app
 *   was open.
 *
 * Both are best-effort: any failure (no connectivity, server unreachable) is
 * swallowed. The local file on disk is always the source of truth for what
 * the app can show offline — sync only ever adds freshness, never a
 * requirement for the app to work.
 */
object DiarioSync {
    private const val URL_STR = "https://rossodaviderun.duckdns.org/api/diario"

    // Shared secret, must match "diario_key" in config.json on the server.
    private const val API_KEY = "2YqkCwezMxt3xUKKLXpsE6cer3QQ8"

    fun push(jsonBody: String) {
        Thread {
            try {
                val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Diario-Key", API_KEY)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w("DiarioSync", "push HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("DiarioSync", "push failed: ${e.message}")
            }
        }.start()
    }

    /** Downloads the current server copy as raw JSON text, or null on any failure. */
    suspend fun pull(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("X-Diario-Key", API_KEY)
            }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            conn.disconnect()
            body
        } catch (e: Exception) {
            Log.w("DiarioSync", "pull failed: ${e.message}")
            null
        }
    }
}
