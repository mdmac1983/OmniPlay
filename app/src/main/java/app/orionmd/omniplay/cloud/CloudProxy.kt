package app.orionmd.omniplay.cloud

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny HTTP server bound to 127.0.0.1 that streams cloud files to the on-device players. ExoPlayer can't add the right
 * auth header (or decrypt an end-to-end-encrypted file) by itself, so cloud items play from here instead:
 *
 *   omni://<account>/<file>   →   http://127.0.0.1:<port>/s/<token>
 *
 * It handles HTTP Range so you can seek: the byte offset is passed to [Cloud.open], which (for Mega / Filen) returns a
 * stream already decrypted from that offset. Nothing is written to disk — bytes are piped straight through.
 */
object CloudProxy {
    private var server: ServerSocket? = null
    @Volatile var port = 0; private set
    private val pool = Executors.newCachedThreadPool()
    private val tokens = ConcurrentHashMap<String, Pair<String, String>>()   // token -> (accountId, fileId)

    /** The last OAuth redirect captured on the loopback (query params), consumed by [AuthFlow]. */
    @Volatile var lastOAuth: Map<String, String>? = null
    fun oauthRedirect(): String { ensure(); return "http://127.0.0.1:$port/oauth" }

    @Synchronized fun ensure() {
        if (server != null) return
        val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = s; port = s.localPort
        thread(isDaemon = true, name = "cloud-proxy") {
            while (true) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(sock) }
            }
        }
    }

    /** A local URL for ExoPlayer to play this cloud item. */
    fun url(accountId: String, fileId: String): String {
        ensure()
        val token = Integer.toHexString((accountId + "|" + fileId).hashCode()) + "_" + fileId.hashCode().toUInt().toString(16)
        tokens[token] = accountId to fileId
        return "http://127.0.0.1:$port/s/$token"
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = sock.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            var range = 0L; var hasRange = false
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) {
                    val m = Regex("bytes=(\\d+)-").find(line); if (m != null) { range = m.groupValues[1].toLong(); hasRange = true }
                }
            }
            val out = sock.getOutputStream()
            if (requestLine.startsWith("HEAD")) { out.write("HTTP/1.1 200 OK\r\nAccept-Ranges: bytes\r\n\r\n".toByteArray()); out.flush(); return }
            val path = requestLine.split(' ').getOrNull(1) ?: return
            if (path.startsWith("/oauth")) {
                val q = path.substringAfter('?', "")
                lastOAuth = q.split('&').mapNotNull { val i = it.indexOf('='); if (i < 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8") }.toMap()
                val html = "<!doctype html><meta name=viewport content='width=device-width'><body style='background:#0B0820;color:#eee;font-family:sans-serif;text-align:center;padding-top:40%'><h2>Media Hub is connected</h2><p>You can close this tab and return to the app.</p>"
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray()); out.flush(); return
            }
            val token = URLDecoder.decode(path.removePrefix("/s/"), "UTF-8")
            val (acctId, fileId) = tokens[token] ?: run { out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); out.flush(); return }
            val acct = Cloud.account(acctId) ?: run { out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); out.flush(); return }
            val stream = try { Cloud.open(acct, fileId, if (hasRange) range else 0L) } catch (_: Exception) { out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush(); return }
            stream.use { cs ->
                val total = cs.length
                val ct = cs.contentType ?: "application/octet-stream"
                val sb = StringBuilder()
                if (hasRange && total > 0) {
                    sb.append("HTTP/1.1 206 Partial Content\r\n")
                    sb.append("Content-Range: bytes $range-${total - 1}/$total\r\n")
                    if (total - range >= 0) sb.append("Content-Length: ${total - range}\r\n")
                } else {
                    sb.append("HTTP/1.1 200 OK\r\n")
                    if (total > 0) sb.append("Content-Length: $total\r\n")
                }
                sb.append("Accept-Ranges: bytes\r\n").append("Content-Type: $ct\r\n").append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                pipe(cs.input, out)
                out.flush()
            }
        } catch (_: Exception) {
        } finally { try { sock.close() } catch (_: Exception) {} }
    }

    private fun pipe(input: java.io.InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n < 0) break
            try { out.write(buf, 0, n) } catch (_: Exception) { break }   // client seeked / closed
        }
    }
}
