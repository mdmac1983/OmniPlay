package app.orionmd.omniplay.cloud

import app.orionmd.omniplay.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Shared HTTP helpers for the cloud providers. */
internal object Http {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    fun req(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = 40000
        c.setRequestProperty("User-Agent", Net.UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
        }
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text, c.headerFields ?: emptyMap())
        } finally { c.disconnect() }
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val r = req(url, "GET", headers); if (r.code !in 200..299) throw CloudError(r.code, r.body); return JSONObject(r.body)
    }
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()
        val r = req(url, "POST", headers, b, "application/x-www-form-urlencoded"); if (r.code !in 200..299) throw CloudError(r.code, r.body); return JSONObject(r.body)
    }
    fun postJson(url: String, json: Any, headers: Map<String, String> = emptyMap()): JSONObject {
        val r = req(url, "POST", headers, json.toString().toByteArray(), "application/json"); if (r.code !in 200..299) throw CloudError(r.code, r.body); return JSONObject(r.body)
    }

    /** Opens [url] for streaming from [offset] (HTTP Range). Returns the stream, its total length, and the MIME type. */
    fun openRange(url: String, offset: Long, headers: Map<String, String> = emptyMap()): CloudStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 40000
        c.setRequestProperty("User-Agent", Net.UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        val code = c.responseCode
        if (code !in 200..299) { val e = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""; c.disconnect(); throw CloudError(code, e) }
        // total length: from Content-Range when ranged, else Content-Length (+ offset when the server honoured the range)
        val cr = c.getHeaderField("Content-Range")
        val ignoredRange = offset > 0 && code == 200 && cr == null   // server sent the whole file from the start
        val len = c.getHeaderFieldLong("Content-Length", -1L)
        val total = cr?.substringAfter('/')?.toLongOrNull() ?: (len.takeIf { it >= 0 }?.plus(if (ignoredRange) 0 else offset) ?: -1L)
        val ct = c.contentType
        val stream = object : InputStream() {
            val inner = c.inputStream.also { s ->
                // the server ignored "Range": skip to the offset ourselves so callers always get bytes from where they asked
                if (ignoredRange) { var left = offset; val buf = ByteArray(64 * 1024); while (left > 0) { val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; left -= n } }
            }
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun close() { try { inner.close() } catch (_: Exception) {}; c.disconnect() }
        }
        return CloudStream(stream, total, ct)
    }
}

class CloudError(val code: Int, val detail: String) : Exception("Cloud error $code: ${detail.take(200)}")

internal fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
