package uk.zjd.cloudpass

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ HTTP: код, тело, заголовки (имена в нижнем регистре). */
class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Простой клиент на HttpURLConnection: без cookie, без Referer, с таймаутами. */
object Net {
    const val UA = "CloudPass-Android"

    @Throws(IOException::class)
    fun request(method: String, url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
                timeoutMs: Int = 20000): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs * 3
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", true) })
                    c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v != null && v.isNotEmpty()) h[k.lowercase()] = v[0] }
            return HttpResult(code, bytes, h)
        } finally {
            c.disconnect()
        }
    }
}
