package dev.romupdater

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class Release(val version: String, val date: String, val filenUrl: String?, val title: String)

/** Where payload.bin sits inside the OTA zip, plus the headers update_engine needs. */
data class Payload(val offset: Long, val size: Long, val headers: List<String>)

object Core {
    const val THREAD_URL = "https://xdaforums.com/t/4801565/"

    // e.g. "[ROM][17][OOSCAM][UNOFFICIAL] VoltageOS 6.3 [enchilada][12/11/2026] | XDA Forums"
    private val TITLE = Regex("""VoltageOS\s+([\d.]+)\s*\[enchilada]\[(\d{2})/(\d{2})/(\d{4})]""")

    fun parseTitle(title: String, filenUrl: String?): Release? {
        val m = TITLE.find(title) ?: return null
        val (version, day, month, year) = m.destructured
        return Release(version, "$year$month$day", filenUrl, title.substringBefore(" | XDA").trim())
    }

    /** Both dates are yyyyMMdd, so string order is date order. */
    fun isNewer(release: Release, installedDate: String) = release.date > installedDate.take(8)

    /** CHECKSUMS.txt is "name" on one line, then "SHA-256: hex" on the next. */
    fun parseChecksums(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var name: String? = null
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val sha = Regex("""^SHA-?256:\s*([0-9a-fA-F]{64})$""").find(line)
            if (sha != null) name?.let { out[it] = sha.groupValues[1].lowercase() } else name = line
        }
        return out
    }

    fun locatePayload(zip: File): Payload {
        val headers = ZipFile(zip).use { z ->
            val entry = z.getEntry("payload_properties.txt") ?: throw IOException("payload_properties.txt가 없어요")
            z.getInputStream(entry).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        RandomAccessFile(zip, "r").use { raf ->
            val tailLen = minOf(raf.length(), 65557L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(raf.length() - tailLen)
            raf.readFully(tail)
            val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            var eocd = tailLen - 22
            while (eocd >= 0 && tb.getInt(eocd) != 0x06054b50) eocd--
            if (eocd < 0) throw IOException("ZIP 파일이 아니에요")
            val cdSize = tb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = tb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset == 0xFFFFFFFFL) throw IOException("ZIP64 파일은 설치할 수 없어요")
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)
            val cb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            var p = 0
            while (p + 46 <= cd.size && cb.getInt(p) == 0x02014b50) {
                val method = cb.getShort(p + 10).toInt()
                val compSize = cb.getInt(p + 20).toLong() and 0xFFFFFFFFL
                val nameLen = cb.getShort(p + 28).toInt() and 0xFFFF
                val extraLen = cb.getShort(p + 30).toInt() and 0xFFFF
                val commentLen = cb.getShort(p + 32).toInt() and 0xFFFF
                val localOffset = cb.getInt(p + 42).toLong() and 0xFFFFFFFFL
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                if (name == "payload.bin") {
                    if (method != 0) throw IOException("payload.bin이 압축되어 있어 설치할 수 없어요")
                    if (compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) throw IOException("ZIP64 파일은 설치할 수 없어요")
                    val local = ByteArray(30)
                    raf.seek(localOffset)
                    raf.readFully(local)
                    val lb = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                    val dataOffset = localOffset + 30 + (lb.getShort(26).toInt() and 0xFFFF) + (lb.getShort(28).toInt() and 0xFFFF)
                    return Payload(dataOffset, compSize, headers)
                }
                p += 46 + nameLen + extraLen + commentLen
            }
        }
        throw IOException("payload.bin이 없어요. 이 기기용 ROM ZIP이 맞는지 확인해 주세요")
    }
}
