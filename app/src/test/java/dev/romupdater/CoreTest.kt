package dev.romupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    @Test
    fun parsesThreadTitle() {
        val r = Core.parseTitle("[ROM][17][OOSCAM][UNOFFICIAL] VoltageOS 6.3 [enchilada][12/11/2026] | XDA Forums", "u")!!
        assertEquals("6.3", r.version)
        assertEquals("20261112", r.date)
        assertEquals("[ROM][17][OOSCAM][UNOFFICIAL] VoltageOS 6.3 [enchilada][12/11/2026]", r.title)
        assertNull(Core.parseTitle("Just a moment...", null))
    }

    @Test
    fun comparesBuildDates() {
        val r = Release("6.4", "20261203", null, "")
        assertTrue(Core.isNewer(r, "20261112"))
        assertFalse(Core.isNewer(r, "20261203-0811"))
        assertFalse(Core.isNewer(r, "20270101"))
    }

    @Test
    fun parsesFilenLinkWithHexKey() {
        val link = Filen.parseLink("https://app.filen.io/#/f/c4a48942-431c-4706-97fe-749e4603e8f1%2361626364" + "61".repeat(28))!!
        assertEquals("c4a48942-431c-4706-97fe-749e4603e8f1", link.uuid)
        assertEquals("abcd" + "a".repeat(28), link.key)
        assertEquals("rawKey_-1", Filen.parseLink("https://app.filen.io/#/f/c4a48942-431c-4706-97fe-749e4603e8f1#rawKey_-1")!!.key)
    }

    @Test
    fun parsesChecksums() {
        val sha = "0e94135a5d16b7181b8b7f448c30eb557e311db59279d7ac980ebd82239e3f60"
        val map = Core.parseChecksums("boot.img\nSHA-256: ${"1".repeat(64)}\n\nrom.zip\nSHA-256: $sha\n")
        assertEquals(sha, map["rom.zip"])
        assertEquals(2, map.size)
    }

    @Test
    fun locatesStoredPayload() {
        val zip = File.createTempFile("ota", ".zip")
        val payload = ByteArray(5000) { (it % 251).toByte() }
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
            out.write("x".toByteArray())
            out.closeEntry()
            val entry = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = CRC32().apply { update(payload) }.value
            }
            out.putNextEntry(entry)
            out.write(payload)
            out.closeEntry()
            out.putNextEntry(ZipEntry("payload_properties.txt"))
            out.write("FILE_HASH=abc\nFILE_SIZE=5000\n".toByteArray())
            out.closeEntry()
        }
        val found = Core.locatePayload(zip)
        assertEquals(5000L, found.size)
        assertEquals(listOf("FILE_HASH=abc", "FILE_SIZE=5000"), found.headers)
        val bytes = zip.readBytes().copyOfRange(found.offset.toInt(), found.offset.toInt() + 5000)
        assertTrue(payload.contentEquals(bytes))
        zip.delete()
    }
}
