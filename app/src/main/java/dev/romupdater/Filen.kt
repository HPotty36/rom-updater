package dev.romupdater

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Reads a Filen public folder link: list the files, then download and decrypt them chunk by chunk. */
object Filen {
    data class Link(val uuid: String, val key: String)
    data class RemoteFile(
        val name: String, val size: Long, val key: String, val uuid: String,
        val region: String, val bucket: String, val chunks: Int, val version: Int,
    )

    const val CHUNK = 1024 * 1024
    private const val PARALLEL = 8

    /** app.filen.io/#/f/<uuid>#<key>; the key is usually hex-encoded and the '#' may arrive as %23. */
    fun parseLink(url: String): Link? {
        val m = Regex("""/f/([0-9a-fA-F-]{36})(?:%23|#)([A-Za-z0-9_-]+)""").find(url) ?: return null
        val raw = m.groupValues[2]
        val key = if (raw.length == 64 && raw.all { it in "0123456789abcdefABCDEF" }) String(hex(raw), Charsets.UTF_8) else raw
        return Link(m.groupValues[1], key)
    }

    fun decryptMetadata(metadata: String, key: String): String {
        if (!metadata.startsWith("002")) throw IOException("읽을 수 없는 Filen 메타데이터 버전이에요: ${metadata.take(3)}")
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec(key.toCharArray(), key.toByteArray(), 1, 256)).encoded
        return String(gcm(derived, metadata.substring(3, 15).toByteArray(), Base64.getDecoder().decode(metadata.substring(15))), Charsets.UTF_8)
    }

    fun decryptChunk(data: ByteArray, key: String, version: Int): ByteArray {
        val keyBytes = when (version) {
            2 -> key.toByteArray()
            3 -> hex(key)
            else -> throw IOException("읽을 수 없는 Filen 파일 버전이에요: $version")
        }
        return gcm(keyBytes, data.copyOfRange(0, 12), data.copyOfRange(12, data.size))
    }

    fun listFiles(link: Link): List<RemoteFile> {
        val info = post("/v3/dir/link/info", JSONObject().put("uuid", link.uuid))
        if (info.optBoolean("hasPassword")) throw IOException("비밀번호가 걸린 Filen 링크는 받을 수 없어요")
        val password = hexOf(MessageDigest.getInstance("SHA-1").digest(hexOf(MessageDigest.getInstance("SHA-512").digest("empty".toByteArray())).toByteArray()))
        val content = post(
            "/v3/dir/link/content",
            JSONObject().put("uuid", link.uuid).put("password", password).put("parent", info.getString("parent")),
        )
        val files = content.getJSONArray("files")
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val meta = JSONObject(decryptMetadata(f.getString("metadata"), link.key))
            RemoteFile(
                meta.getString("name"), meta.getLong("size"), meta.getString("key"), f.getString("uuid"),
                f.getString("region"), f.getString("bucket"), f.getInt("chunks"), f.getInt("version"),
            )
        }
    }

    fun readSmall(file: RemoteFile): ByteArray =
        (0 until file.chunks).fold(ByteArray(0)) { acc, i -> acc + decryptChunk(fetchChunk(file, i), file.key, file.version) }

    /** Resumes from whatever whole chunks [dest] already holds. */
    fun download(file: RemoteFile, dest: File, isStopped: () -> Boolean, onProgress: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        var index = if (dest.exists()) (dest.length() / CHUNK).toInt().coerceAtMost(file.chunks) else 0
        // Chunks are fetched several at a time and written in order.
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            RandomAccessFile(dest, "rw").use { raf ->
                raf.setLength(index.toLong() * CHUNK)
                raf.seek(raf.length())
                onProgress(raf.length())
                val pending = ArrayDeque<Future<ByteArray>>()
                var next = index
                while (index < file.chunks) {
                    while (next < file.chunks && pending.size < PARALLEL * 2) {
                        val i = next++
                        pending.add(pool.submit(Callable { decryptChunk(fetchChunk(file, i), file.key, file.version) }))
                    }
                    if (isStopped()) throw IOException("받기를 취소했어요")
                    val data = try {
                        pending.removeFirst().get()
                    } catch (e: ExecutionException) {
                        throw e.cause as? IOException ?: IOException(e.cause)
                    }
                    raf.write(data)
                    index++
                    onProgress(raf.filePointer)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (dest.length() != file.size) throw IOException("받은 파일 크기가 달라요: ${dest.length()} / ${file.size}")
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hexOf(md.digest())
    }

    private fun fetchChunk(file: RemoteFile, index: Int): ByteArray {
        var last: IOException? = null
        repeat(5) { attempt ->
            try {
                val c = URL("https://egest.filen.io/${file.region}/${file.bucket}/${file.uuid}/$index").openConnection() as HttpURLConnection
                c.connectTimeout = 30_000
                c.readTimeout = 60_000
                if (c.responseCode != 200) throw IOException("Filen 응답 ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } catch (e: IOException) {
                last = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw last ?: IOException("$index 번째 조각을 받지 못했어요")
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val c = URL("https://gateway.filen.io$endpoint").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        if (!json.optBoolean("status")) throw IOException("Filen: ${json.optString("message")}")
        return json.getJSONObject("data")
    }

    private fun gcm(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
