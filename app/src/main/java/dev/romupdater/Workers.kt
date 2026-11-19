package dev.romupdater

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Repo.check(applicationContext)) Result.success() else Result.retry()

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = Repo.state.value.latest?.filenUrl
        val link = url?.let { Filen.parseLink(it) }
        if (link == null) {
            Repo.state.update { it.copy(message = "스레드에서 다운로드 링크를 찾지 못했어요. 브라우저로 받은 ZIP 파일로 설치해 주세요") }
            return Result.failure()
        }
        Repo.state.update { it.copy(downloading = true, downloadFraction = 0f, message = null) }
        setForeground(foreground(0))
        return try {
            val zip = withContext(Dispatchers.IO) { download(link) }
            Repo.state.update { it.copy(downloading = false, zipPath = zip.absolutePath) }
            Result.success()
        } catch (e: Exception) {
            Repo.state.update { it.copy(downloading = false, message = "${e.message ?: "받지 못했어요"}. 다운로드를 다시 누르면 이어서 받아요") }
            Result.failure()
        }
    }

    private suspend fun download(link: Filen.Link): File {
        val files = Filen.listFiles(link)
        val rom = files.filter { it.name.endsWith(".zip") }.minByOrNull { it.name.length }
            ?: throw IllegalStateException("공유 폴더에 ROM ZIP이 없어요")
        val dir = Repo.romDir(applicationContext)
        dir.listFiles()?.filter { it.name != rom.name + ".part" }?.forEach { it.delete() }
        val part = File(dir, rom.name + ".part")
        var shown = -1
        Filen.download(rom, part, { isStopped }) { done ->
            val fraction = done.toFloat() / rom.size
            Repo.state.update { it.copy(downloadFraction = fraction) }
            val percent = (fraction * 100).toInt()
            if (percent != shown) {
                shown = percent
                setForegroundAsync(foreground(percent))
            }
        }
        val expected = files.firstOrNull { it.name == "CHECKSUMS.txt" }
            ?.let { Core.parseChecksums(String(Filen.readSmall(it), Charsets.UTF_8))[rom.name] }
        if (expected != null && Filen.sha256(part) != expected) {
            part.delete()
            throw IllegalStateException("받은 파일이 원본과 달라서 지웠어요")
        }
        val zip = File(dir, rom.name)
        part.renameTo(zip)
        return zip
    }

    private fun foreground(percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Repo.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("새 빌드를 받고 있어요")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val NAME = "download"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
