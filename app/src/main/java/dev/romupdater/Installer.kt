package dev.romupdater

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Base64

object Root {
    /** Runs [command] as root, feeding each output line to [onLine]. Returns the exit code, or -1 when su is missing. */
    fun exec(command: String, onLine: (String) -> Unit = {}): Int = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().forEachLine(onLine)
        process.waitFor()
    } catch (e: IOException) {
        -1
    }

    fun available(): Boolean {
        var uid = ""
        return exec("id -u") { uid = it.trim() } == 0 && uid == "0"
    }
}

object Installer {
    private const val OTA = "/data/ota_package/update.zip"

    // update_engine status codes
    private val STAGES = mapOf(3 to "쓰는 중", 4 to "검증하는 중", 5 to "마무리하는 중", 6 to "마무리하는 중")

    sealed interface Result {
        /** The new build is on the other slot. [rootKept] says whether Magisk was carried over. */
        data class Installed(val rootKept: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    fun install(context: Context, zip: File, log: (String) -> Unit, onProgress: (String, Float) -> Unit): Result {
        if (!Root.available()) return Result.Failed("루트 권한이 없어요. Magisk 앱의 슈퍼유저 탭에서 이 앱을 허용해 주세요")
        val payload = try {
            Core.locatePayload(zip)
        } catch (e: IOException) {
            return Result.Failed(e.message ?: "ZIP을 읽지 못했어요")
        }

        onProgress("파일 준비하는 중", 0f)
        val staged = Root.exec(
            "mkdir -p /data/ota_package && cp -f '${zip.absolutePath}' $OTA && chown system:cache $OTA && " +
                "chmod 660 $OTA && chcon u:object_r:ota_package_file:s0 $OTA",
            log,
        )
        if (staged != 0) return Result.Failed("ZIP을 설치 위치로 옮기지 못했어요 (코드 $staged)")

        val headers = Base64.getEncoder().encodeToString(payload.headers.joinToString("\n").toByteArray())
        var error: String? = null
        var doneCode: Int? = null
        Root.exec(
            "CLASSPATH='${context.packageCodePath}' app_process /system/bin dev.romupdater.EngineRunner " +
                "file://$OTA ${payload.offset} ${payload.size} $headers",
        ) { line ->
            val parts = line.split(" ")
            when (parts[0]) {
                "STATUS" -> {
                    val status = parts.getOrNull(1)?.toIntOrNull() ?: return@exec
                    STAGES[status]?.let { onProgress(it, parts.getOrNull(2)?.toFloatOrNull() ?: 0f) }
                }
                "DONE" -> doneCode = parts.getOrNull(1)?.toIntOrNull()
                "ERROR" -> error = line.removePrefix("ERROR ")
                else -> log(line)
            }
        }
        Root.exec("rm -f $OTA")
        if (doneCode != 0) {
            return Result.Failed(error ?: "설치하지 못했어요 (update_engine 코드 ${doneCode ?: "없음"})")
        }

        onProgress("루트 지키는 중", 1f)
        val script = File(context.cacheDir, "keep_root.sh")
        script.writeText(KEEP_ROOT)
        var patched = false
        Root.exec("sh '${script.absolutePath}'") { line ->
            if (line == "PATCH_OK") patched = true else log(line)
        }
        return Result.Installed(patched)
    }

    fun reboot() {
        Root.exec("svc power reboot || reboot")
    }

    /**
     * Patches the freshly written boot image on the other slot with the installed Magisk app's own
     * tools, the same steps the Magisk app runs for "install to inactive slot".
     */
    private val KEEP_ROOT = """
        [ "${'$'}(getprop ro.boot.slot_suffix)" = "_a" ] && T=_b || T=_a
        APK=${'$'}(pm path com.topjohnwu.magisk | head -n 1 | cut -d: -f2)
        [ -n "${'$'}APK" ] || { echo "Magisk 앱이 설치되어 있지 않아요"; exit 3; }
        D=/data/local/tmp/romupdater_magisk
        rm -rf ${'$'}D; mkdir -p ${'$'}D; cd ${'$'}D || exit 4
        unzip -o -q -j "${'$'}APK" 'lib/arm64-v8a/*' assets/boot_patch.sh assets/util_functions.sh || exit 5
        for f in magiskboot magiskinit magisk init-ld busybox; do mv lib${'$'}f.so ${'$'}f || exit 6; done
        chmod 755 *
        BLK=/dev/block/by-name/boot${'$'}T
        dd if=${'$'}BLK of=boot.img bs=1M 2>/dev/null || exit 7
        [ "${'$'}(getprop ro.crypto.state)" = "encrypted" ] && ENC=true || ENC=false
        grep ' / ' /proc/mounts | grep -q '/dev/root' && LSAR=true || LSAR=false
        KEEPVERITY=true KEEPFORCEENCRYPT=${'$'}ENC PATCHVBMETAFLAG=false RECOVERYMODE=false LEGACYSAR=${'$'}LSAR \
          sh ./boot_patch.sh boot.img >patch.log 2>&1 || { tail -n 5 patch.log; exit 8; }
        blockdev --setrw ${'$'}BLK 2>/dev/null
        dd if=new-boot.img of=${'$'}BLK bs=1M 2>/dev/null || exit 9
        sync
        cd /; rm -rf ${'$'}D
        echo PATCH_OK
    """.trimIndent()
}
