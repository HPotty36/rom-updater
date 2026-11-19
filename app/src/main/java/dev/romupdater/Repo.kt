package dev.romupdater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

data class UiState(
    val installedDate: String = "",
    val latest: Release? = null,
    val lastCheck: Long = 0,
    val checking: Boolean = false,
    val checkError: String? = null,
    val downloading: Boolean = false,
    val downloadFraction: Float = 0f,
    val zipPath: String? = null,
    val installing: Boolean = false,
    val stage: String = "",
    val stageFraction: Float = 0f,
    val installed: Installer.Result.Installed? = null,
    val message: String? = null,
) {
    val updateAvailable get() = latest != null && Core.isNewer(latest, installedDate)
}

object Repo {
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_PROGRESS = "progress"
    val state = MutableStateFlow(UiState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun romDir(context: Context) = File(context.filesDir, "rom").apply { mkdirs() }

    fun init(context: Context, installedOverride: String? = null) {
        val prefs = prefs(context)
        // Build.TIME is the ROM build time; the thread title carries the same date.
        val installed = installedOverride
            ?: SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(Build.TIME))
        val latest = prefs.getString("date", null)?.let {
            Release(prefs.getString("version", "")!!, it, prefs.getString("url", null), prefs.getString("title", "")!!)
        }
        val zip = romDir(context).listFiles()?.firstOrNull { it.name.endsWith(".zip") }
        state.update { it.copy(installedDate = installed, latest = latest, lastCheck = prefs.getLong("lastCheck", 0), zipPath = zip?.absolutePath) }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "새 빌드 알림", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "다운로드 진행", NotificationManager.IMPORTANCE_LOW))
    }

    /** Looks at the XDA thread and notifies once per new build. Returns false when the thread could not be read. */
    suspend fun check(context: Context): Boolean {
        state.update { it.copy(checking = true, checkError = null) }
        val release = try {
            fetchThread(context)
        } catch (e: Exception) {
            state.update { it.copy(checking = false, checkError = "XDA 스레드를 읽지 못했어요. 잠시 뒤에 다시 확인해 주세요") }
            return false
        }
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        prefs.edit().putString("date", release.date).putString("version", release.version)
            .putString("url", release.filenUrl).putString("title", release.title).putLong("lastCheck", now).apply()
        state.update { it.copy(checking = false, latest = release, lastCheck = now) }
        if (Core.isNewer(release, state.value.installedDate) && prefs.getString("notified", null) != release.date) {
            notifyUpdate(context, release)
            prefs.edit().putString("notified", release.date).apply()
        }
        return true
    }

    fun install(context: Context) {
        val zip = state.value.zipPath?.let { File(it) } ?: return
        state.update { it.copy(installing = true, stage = "준비하는 중", stageFraction = 0f, message = null) }
        scope.launch(Dispatchers.IO) {
            val log = StringBuilder()
            val result = Installer.install(context.applicationContext, zip, { log.appendLine(it) }) { stage, fraction ->
                state.update { it.copy(stage = stage, stageFraction = fraction) }
            }
            when (result) {
                is Installer.Result.Installed -> {
                    zip.delete()
                    state.update { it.copy(installing = false, installed = result, zipPath = null, message = log.toString().trim().ifEmpty { null }) }
                }
                is Installer.Result.Failed -> {
                    val detail = log.toString().trim()
                    state.update { it.copy(installing = false, message = if (detail.isEmpty()) result.reason else "${result.reason}\n$detail") }
                }
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    private fun notifyUpdate(context: Context, release: Release) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("VoltageOS ${release.version} 새 빌드가 나왔어요")
            .setContentText("${formatDate(release.date)} 빌드예요. 눌러서 받아 보세요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    fun formatDate(yyyymmdd: String) =
        if (yyyymmdd.length >= 8) "${yyyymmdd.substring(0, 4)}-${yyyymmdd.substring(4, 6)}-${yyyymmdd.substring(6, 8)}" else yyyymmdd

    /**
     * Opens the thread in an off-screen WebView, the way a browser would, and reads the title and
     * the first Filen link of the first post. XDA turns away plain HTTP clients.
     */
    private suspend fun fetchThread(context: Context): Release = withContext(Dispatchers.Main) {
        withTimeout(60_000) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context.applicationContext)
                val handler = Handler(Looper.getMainLooper())
                var polling = false
                var finished = false
                val js = "(function(){var p=document.querySelector('article.message .bbWrapper');" +
                    "var a=p?p.querySelector('a[href*=\"filen.io\"]'):null;" +
                    "return JSON.stringify({title:document.title,link:a?a.href:''});})()"

                fun poll() {
                    if (finished) return
                    webView.evaluateJavascript(js) { raw ->
                        if (finished) return@evaluateJavascript
                        val release = try {
                            val obj = JSONObject(JSONTokener(raw).nextValue() as String)
                            Core.parseTitle(obj.getString("title"), obj.getString("link").ifEmpty { null })
                        } catch (e: Exception) {
                            null
                        }
                        if (release != null) {
                            finished = true
                            webView.destroy()
                            cont.resume(release)
                        } else {
                            // Still on an interstitial page; look again shortly.
                            handler.postDelayed({ poll() }, 2000)
                        }
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!polling) {
                            polling = true
                            poll()
                        }
                    }
                }
                cont.invokeOnCancellation {
                    handler.post {
                        finished = true
                        webView.destroy()
                    }
                }
                webView.loadUrl(Core.THREAD_URL)
            }
        }
    }
}
