package app.orionmd.omniplay

import app.orionmd.omniplay.cloud.Http
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Resuming a download from a byte offset must give exactly the rest of the file — whether or not the server supports Range. */
class ResumeTest {
    private val data = ByteArray(300_000) { (it * 31 + 7).toByte() }

    /** A tiny HTTP server (one request at a time) that does or doesn't honour "Range". */
    private fun serve(honourRange: Boolean, block: (String) -> Unit) {
        val ss = ServerSocket(0)
        val t = thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                sock.use { c ->
                    val r = c.getInputStream().bufferedReader()
                    var range: String? = null
                    while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
                    val from = if (honourRange && range != null) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
                    val head = if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${data.size - 1}/${data.size}\r\nContent-Length: ${data.size - from}\r\n"
                               else "HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\n"
                    val out = c.getOutputStream()
                    out.write((head + "Connection: close\r\n\r\n").toByteArray()); out.write(data, from, data.size - from); out.flush()
                }
            }
        }
        try { block("http://127.0.0.1:${ss.localPort}/f") } finally { ss.close(); t.join(1000) }
    }

    private fun resumeAt(url: String, offset: Int) {
        val s = Http.openRange(url, offset.toLong())
        val got = s.input.use { it.readBytes() }
        assertEquals(data.size.toLong(), s.length)
        assertArrayEquals(data.copyOfRange(offset, data.size), got)
    }

    @Test fun server_with_range() = serve(true) { u -> resumeAt(u, 0); resumeAt(u, 123_457); resumeAt(u, 299_999) }
    @Test fun server_without_range() = serve(false) { u -> resumeAt(u, 0); resumeAt(u, 123_457); resumeAt(u, 299_999) }
}
