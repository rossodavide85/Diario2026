package it.davide.diario

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mirrors the local diary to the personal "La Corsa" server after every save,
 * so it can be read remotely (e.g. by Claude) without any manual export.
 *
 * Fire-and-forget: this runs on a plain background thread and swallows any
 * failure (no connectivity, server unreachable). The local save on disk has
 * already succeeded regardless — this is just a best-effort mirror, never a
 * requirement for the app to work offline.
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
}
