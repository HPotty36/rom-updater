package dev.romupdater

import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Runs as root through app_process, outside the app's own process, because only a privileged
 * caller may talk to update_engine. Prints one line per event for [Installer] to read:
 * "STATUS <code> <fraction>", "DONE <errorCode>" or "ERROR <message>".
 */
object EngineRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val headers = String(Base64.getDecoder().decode(args[3]), Charsets.UTF_8).split("\n").filter { it.isNotBlank() }.toTypedArray()
        val done = CountDownLatch(1)
        var result = -1
        try {
            val engine = UpdateEngine()
            engine.bind(object : UpdateEngineCallback() {
                override fun onStatusUpdate(status: Int, percent: Float) {
                    println("STATUS $status $percent")
                }

                override fun onPayloadApplicationComplete(errorCode: Int) {
                    result = errorCode
                    done.countDown()
                }
            })
            engine.applyPayload(args[0], args[1].toLong(), args[2].toLong(), headers)
        } catch (e: Throwable) {
            println("ERROR ${e.message ?: e}")
            exitProcess(2)
        }
        done.await()
        println("DONE $result")
        exitProcess(if (result == 0) 0 else 1)
    }
}
