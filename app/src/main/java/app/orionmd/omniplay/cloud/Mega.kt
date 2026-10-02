package app.orionmd.omniplay.cloud

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MEGA (mega.nz). End-to-end encrypted: you sign in with your email and password; the files are decrypted on this device
 * as they stream, so nothing unencrypted is stored. Implements MEGA's documented login (account versions 1 and 2),
 * node/attribute decryption, and AES-CTR streaming with HTTP range so you can seek.
 */
object Mega : CloudProvider {
    override val id = "mega"
    override val name = "MEGA"
    override val auth = "password"
    override val signupUrl = "https://mega.nz/register"
    override val help = "Sign in with your MEGA email and password. Files are end-to-end encrypted and are decrypted on your device as they play. Tip: two-factor accounts aren't supported here — use an account without 2FA, or MEGA's file-request/export links via WebDAV."

    private const val API = "https://g.api.mega.co.nz/cs"
    private var seq = (Math.random() * 1_000_000_000).toLong()

    // ---------- low-level ----------
    private fun b64dec(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/'); while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.NO_WRAP)
    }
    private fun b64enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP).replace('+', '-').replace('/', '_').trimEnd('=')
    private fun a32(b: ByteArray): IntArray { val p = b.copyOf((b.size + 3) / 4 * 4); val o = IntArray(p.size / 4); val bb = ByteBuffer.wrap(p); for (i in o.indices) o[i] = bb.int; return o }
    private fun bytes(a: IntArray): ByteArray { val bb = ByteBuffer.allocate(a.size * 4); a.forEach { bb.putInt(it) }; return bb.array() }
    private fun aesEcbEnc(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)
    private fun aesEcbDec(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)
    private fun aesCbcDec(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16))) }.doFinal(data)

    private fun prepareKey(password: IntArray): ByteArray {
        var pkey = intArrayOf(0x93C467E3.toInt(), 0x7DB0C7A4, 0xD1BE3F81.toInt(), 0x0152CB56)
        repeat(0x10000) {
            var j = 0
            while (j < password.size) {
                val sub = IntArray(4); for (k in 0 until 4) sub[k] = if (j + k < password.size) password[j + k] else 0
                pkey = a32(aesEcbEnc(bytes(sub), bytes(pkey))); j += 4
            }
        }
        return bytes(pkey)
    }
    private fun stringHash(s: String, aesKey: ByteArray): String {
        val sb = s.toByteArray(Charsets.UTF_8); val h = IntArray(4)
        val sa = a32(sb)
        for (i in sa.indices) h[i % 4] = h[i % 4] xor sa[i]
        var hh = bytes(h)
        repeat(0x4000) { hh = aesEcbEnc(aesKey, hh) }
        val r = a32(hh)
        return b64enc(bytes(intArrayOf(r[0], r[2])))
    }

    private fun apiReq(sid: String?, req: JSONObject): JSONObject {
        seq++
        val url = "$API?id=$seq" + (if (sid != null) "&sid=$sid" else "")
        val r = Http.req(url, "POST", emptyMap(), JSONArray().put(req).toString().toByteArray(), "application/json")
        val arr = JSONArray(r.body.ifEmpty { "[]" })
        val first = arr.opt(0)
        if (first is Int) throw Exception("MEGA error $first" + if (first == -9) " (not found)" else if (first == -16) " (account blocked)" else if (first == -26) " (two-factor is on — not supported)" else "")
        return first as? JSONObject ?: throw Exception("MEGA: unexpected response")
    }

    // ---------- login ----------
    override fun signIn(fields: Map<String, String>): JSONObject {
        val email = fields["user"]?.trim()?.lowercase() ?: throw Exception("Enter your MEGA email")
        val password = fields["pass"] ?: throw Exception("Enter your password")
        // account version?
        val pre = apiReq(null, JSONObject().put("a", "us0").put("user", email))
        val version = pre.optInt("v", 1)
        val passwordKey: ByteArray; val uh: String
        if (version >= 2) {
            val salt = b64dec(pre.optString("s"))
            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 100_000, 512)
            val derived = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
            passwordKey = derived.copyOfRange(0, 16)
            uh = b64enc(derived.copyOfRange(16, 32))
        } else {
            passwordKey = prepareKey(a32(password.toByteArray(Charsets.UTF_8)))
            uh = stringHash(email, passwordKey)
        }
        val res = apiReq(null, JSONObject().put("a", "us").put("user", email).put("uh", uh))
        val masterKey = aesEcbDec(passwordKey, b64dec(res.optString("k")))
        // session id via RSA private key
        val privkEnc = b64dec(res.optString("privk"))
        val privk = aesEcbDec(masterKey, privkEnc)
        val rsa = parseRsa(privk)
        val csid = b64dec(res.optString("csid"))
        val m = BigInteger(1, csid)
        val sidInt = m.modPow(rsa.d, rsa.n)
        val sidBytes = toFixed(sidInt, rsa.byteLen)
        val sid = b64enc(sidBytes.copyOfRange(0, 43))
        return JSONObject().put("sid", sid).put("mk", b64enc(masterKey)).put("email", email).put("label", email)
    }

    private class Rsa(val n: BigInteger, val d: BigInteger, val byteLen: Int)
    /** privk = p | q | d | u, each an MPI: 2-byte bit length, then the big-endian bytes. */
    private fun parseRsa(data: ByteArray): Rsa {
        var pos = 0
        fun mpi(): BigInteger { val bits = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff); val len = (bits + 7) / 8; pos += 2; val b = data.copyOfRange(pos, pos + len); pos += len; return BigInteger(1, b) }
        val p = mpi(); val q = mpi(); val d = mpi(); mpi()
        val n = p.multiply(q)
        return Rsa(n, d, (n.bitLength() + 7) / 8)
    }
    private fun toFixed(x: BigInteger, len: Int): ByteArray { val b = x.toByteArray(); val out = ByteArray(len); val src = if (b.size > len) b.copyOfRange(b.size - len, b.size) else b; System.arraycopy(src, 0, out, len - src.size, src.size); return out }

    // ---------- nodes ----------
    private fun sid(a: CloudAccount) = a.data.optString("sid")
    private fun mk(a: CloudAccount) = b64dec(a.data.optString("mk"))

    private fun nodeKey(a: CloudAccount, node: JSONObject): ByteArray? {
        val kField = node.optString("k"); if (kField.isEmpty()) return null
        val part = kField.split("/").firstOrNull { it.contains(":") } ?: return null
        val enc = b64dec(part.substringAfter(":"))
        if (enc.size < 16) return null
        return aesEcbDec(mk(a), enc.copyOf(enc.size / 16 * 16))
    }
    /** For files the stored key is 8 ints; the real AES key is the first 4 XOR the last 4. */
    private fun fileAesKey(decrypted: ByteArray): ByteArray {
        if (decrypted.size < 32) return decrypted.copyOf(16)
        val k = a32(decrypted); val out = IntArray(4) { k[it] xor k[it + 4] }; return bytes(out)
    }
    private fun nonce(decrypted: ByteArray): ByteArray = decrypted.copyOfRange(16, 24)

    private fun decAttr(decrypted: ByteArray, attr: String): String {
        val key = if (decrypted.size >= 32) fileAesKey(decrypted) else decrypted.copyOf(16)
        val raw = aesCbcDec(key, b64dec(attr).let { it.copyOf(it.size / 16 * 16) })
        val s = String(raw, Charsets.UTF_8).trim('\u0000')
        return if (s.startsWith("MEGA")) s.removePrefix("MEGA") else s
    }

    override fun list(acct: CloudAccount, folderId: String): List<CloudFile> {
        val res = apiReq(sid(acct), JSONObject().put("a", "f").put("c", 1))
        val nodes = res.optJSONArray("f") ?: return emptyList()
        // find the root handle if asked for ""
        var root = folderId
        if (root.isEmpty()) root = nodes.objs().firstOrNull { it.optInt("t") == 2 }?.optString("h") ?: ""
        val out = ArrayList<CloudFile>()
        for (n in nodes.objs()) {
            if (n.optString("p") != root) continue
            val t = n.optInt("t")
            if (t != 0 && t != 1) continue
            val dec = nodeKey(acct, n) ?: continue
            val attrs = try { JSONObject(decAttr(dec, n.optString("a"))) } catch (_: Exception) { JSONObject() }
            val name = attrs.optString("n").ifEmpty { n.optString("h") }
            out.add(CloudFile(n.optString("h"), name, t == 1, n.optLong("s"), n.optLong("ts") * 1000))
        }
        return out
    }

    override fun open(acct: CloudAccount, fileId: String, offset: Long): CloudStream {
        val res = apiReq(sid(acct), JSONObject().put("a", "f").put("c", 1))
        val node = res.optJSONArray("f")?.objs()?.firstOrNull { it.optString("h") == fileId } ?: throw Exception("File not found")
        val dec = nodeKey(acct, node) ?: throw Exception("Can't read the file key")
        val aesKey = fileAesKey(dec); val nonce = nonce(dec)
        val dl = apiReq(sid(acct), JSONObject().put("a", "g").put("g", 1).put("n", fileId))
        val url = dl.optString("g"); if (url.isEmpty()) throw Exception(dl.optString("e", "MEGA: no download link"))
        val size = dl.optLong("s")
        // align the HTTP range to a 16-byte block; decrypt from there and skip the few leading bytes
        val blockStart = (offset / 16) * 16
        val src = Http.openRange(url, blockStart)
        val dec2 = CtrStream(src.input, aesKey, nonce, blockStart)
        val skip = (offset - blockStart).toInt()
        if (skip > 0) { val b = ByteArray(skip); var r = 0; while (r < skip) { val n = dec2.read(b, r, skip - r); if (n < 0) break; r += n } }
        return CloudStream(dec2, size, "application/octet-stream")
    }

    /** AES-128-CTR stream: counter = nonce(8) || blockIndex(8 big-endian). */
    private class CtrStream(val src: InputStream, key: ByteArray, val nonce: ByteArray, startByte: Long) : InputStream() {
        private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        init {
            val iv = ByteBuffer.allocate(16); iv.put(nonce); iv.putLong(startByte / 16)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.array()))
        }
        private var buf: ByteArray = ByteArray(0); private var pos = 0
        override fun read(): Int { val b = ByteArray(1); val n = read(b, 0, 1); return if (n < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size) {
                val raw = ByteArray(64 * 1024); val n = src.read(raw); if (n < 0) return -1
                buf = cipher.update(raw, 0, n); pos = 0
                if (buf.isEmpty()) return read(b, off, len)
            }
            val n = minOf(len, buf.size - pos); System.arraycopy(buf, pos, b, off, n); pos += n; return n
        }
        override fun close() { try { src.close() } catch (_: Exception) {} }
    }
}
