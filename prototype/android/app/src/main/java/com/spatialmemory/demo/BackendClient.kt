package com.spatialmemory.demo

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

class BackendClient {
    private var serverMillis = 0L
    private var anchorElapsed = 0L
    fun captureTime(elapsed: Long): String {
        check(serverMillis > 0) { "Connect to the computer before capturing." }
        return Instant.ofEpochMilli(serverMillis + elapsed - anchorElapsed).toString()
    }
    fun request(path: String, body: JSONObject? = null): JSONObject = exchange(path, body?.toString()?.toByteArray(), "application/json")
    fun upload(path: String, metadata: JSONObject, file: File): JSONObject {
        val boundary = "spatial-${UUID.randomUUID()}"
        val bytes = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n$metadata\r\n".toByteArray())
            write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"capture.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(this) }
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return exchange(path, bytes, "multipart/form-data; boundary=$boundary")
    }
    private fun exchange(path: String, body: ByteArray?, contentType: String): JSONObject {
        val connection = URL(BuildConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
        val started = SystemClock.elapsedRealtime()
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 65000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.PAIRING_TOKEN)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: error("Computer did not respond")
            val value = JSONObject(text)
            if (code !in 200..299) error(value.optString("message", "Computer returned an error"))
            if (path == "/v1/setup" || path == "/v1/session") {
                val ended = SystemClock.elapsedRealtime()
                check(connection.date > 0 && ended - started < 5000) { "Clock sync failed. Try Start / Resume." }
                serverMillis = connection.date
                anchorElapsed = (started + ended) / 2
            }
            return value
        } finally { connection.disconnect() }
    }
}
