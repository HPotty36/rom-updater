package dev.romupdater

import android.Manifest
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One accent colour on a quiet grey ground; everything else is ink and two greys. */
private data class Palette(
    val ground: Color, val card: Color, val ink: Color, val soft: Color, val faint: Color,
    val accent: Color, val onAccent: Color, val quiet: Color, val danger: Color, val dangerGround: Color,
)

private val Light = Palette(
    ground = Color(0xFFF3F4F6), card = Color.White, ink = Color(0xFF17191C), soft = Color(0xFF6C7480), faint = Color(0xFFE6E8EB),
    accent = Color(0xFF2B6FF3), onAccent = Color.White, quiet = Color(0xFFE6E8EB), danger = Color(0xFFD93A3F), dangerGround = Color(0xFFFCEBEC),
)
private val Dark = Palette(
    ground = Color(0xFF0E0F11), card = Color(0xFF1A1B1F), ink = Color(0xFFF2F3F5), soft = Color(0xFF9AA1AC), faint = Color(0xFF2A2C31),
    accent = Color(0xFF4C8DFF), onAccent = Color.White, quiet = Color(0xFF2A2C31), danger = Color(0xFFFF7B7E), dangerGround = Color(0xFF34191B),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds can pretend to run an older build, to exercise the update path: --es installedDate 20261001
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Repo.init(this, if (debuggable) intent.getStringExtra("installedDate") else null)
        CheckWorker.schedule(this)
        setContent { Screen(if (isSystemInDarkTheme()) Dark else Light) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(c: Palette) {
        val context = LocalContext.current
        val state by Repo.state.collectAsState()
        var confirmInstall by remember { mutableStateOf(false) }

        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importZip(uri)
        }
        // Keep the screen on while the ROM is being written.
        LaunchedEffect(state.installing) {
            if (state.installing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val done = state.installed
        val latest = state.latest
        val headline: String
        val detail: String
        when {
            done != null && done.rootKept -> {
                headline = "설치가 끝났어요"
                detail = "재부팅하면 새 빌드로 켜져요. 루트도 그대로예요."
            }
            done != null -> {
                headline = "설치는 끝났는데\n루트를 지키지 못했어요"
                detail = "재부팅하기 전에 Magisk 앱에서 설치 → 비활성 슬롯에 설치를 눌러 주세요. 그냥 재부팅하면 루트가 풀려요."
            }
            state.installing -> {
                headline = "새 빌드를 설치하고 있어요"
                detail = "폰은 그대로 써도 되지만 전원은 끄지 마세요."
            }
            state.downloading -> {
                headline = "새 빌드를 받고 있어요"
                detail = "다른 앱을 써도 계속 받아요."
            }
            state.zipPath != null -> {
                headline = "설치할 준비가 됐어요"
                detail = File(state.zipPath!!).name
            }
            state.updateAvailable -> {
                headline = "VoltageOS ${latest!!.version}\n새 빌드가 나왔어요"
                detail = "${Repo.formatDate(latest.date)}에 올라온 빌드예요."
            }
            latest == null -> {
                headline = "새 빌드가 있는지\n아직 확인하지 않았어요"
                detail = "한 번 확인하면 그다음부터는 6시간마다 알아서 확인해요."
            }
            else -> {
                headline = "최신 빌드를 쓰고 있어요"
                detail = "새 빌드가 올라오면 알림으로 알려 드릴게요."
            }
        }

        Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 168.dp),
            ) {
                Text("ROM 업데이터", color = c.soft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(28.dp))
                Text(headline, color = c.ink, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(detail, color = c.soft, fontSize = 16.sp, lineHeight = 24.sp)

                if (state.installing || state.downloading) {
                    val fraction = if (state.installing) state.stageFraction else state.downloadFraction
                    Spacer(Modifier.height(32.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(fraction * 100).toInt()}", color = c.ink, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                        Text("%", color = c.soft, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                        if (state.installing) {
                            Spacer(Modifier.weight(1f))
                            Text(state.stage, color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.faint)) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
                    }
                }

                Spacer(Modifier.height(32.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    InfoRow(c, "설치된 빌드", Repo.formatDate(state.installedDate))
                    InfoRow(c, "최신 빌드", latest?.let { "${it.version} · ${Repo.formatDate(it.date)}" } ?: "확인 전")
                    InfoRow(
                        c, "마지막 확인",
                        if (state.lastCheck > 0) DateUtils.getRelativeTimeSpanString(state.lastCheck).toString() else "없음",
                    )
                }

                (state.message ?: state.checkError)?.let { problem ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        problem, color = c.danger, fontSize = 14.sp, lineHeight = 21.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.dangerGround).padding(16.dp),
                    )
                }
            }

            // The main action always sits in the same place at the bottom.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.ground).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val check = { Repo.scope.launch { Repo.check(context.applicationContext) }; Unit }
                when {
                    done != null -> BigButton(c, "재부팅") { Repo.scope.launch(Dispatchers.IO) { Installer.reboot() } }
                    state.installing -> BigButton(c, "설치하는 중", enabled = false) {}
                    state.downloading -> BigButton(c, "받기 취소", quiet = true) { DownloadWorker.cancel(context) }
                    state.zipPath != null -> {
                        BigButton(c, "설치하기") { confirmInstall = true }
                        SmallAction(c, "받아 둔 파일 지우기") {
                            File(state.zipPath!!).delete()
                            Repo.state.update { it.copy(zipPath = null) }
                        }
                    }
                    state.updateAvailable -> {
                        BigButton(c, "다운로드") { DownloadWorker.start(context) }
                        SmallAction(c, if (state.checking) "확인하는 중" else "다시 확인", enabled = !state.checking, onClick = check)
                    }
                    else -> {
                        BigButton(c, if (state.checking) "확인하는 중" else "지금 확인", enabled = !state.checking, onClick = check)
                        SmallAction(c, "받아 둔 ZIP 파일로 설치", enabled = !state.checking) {
                            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                    }
                }
            }
        }

        if (confirmInstall) {
            ModalBottomSheet(
                onDismissRequest = { confirmInstall = false },
                containerColor = c.card,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                dragHandle = null,
            ) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp).navigationBarsPadding()) {
                    Text("이 빌드를 설치할까요?", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "지금 쓰는 쪽은 그대로 두고 반대쪽 슬롯에 설치해요. 재부팅하면 새 빌드로 바뀌고, 데이터는 지워지지 않아요.",
                        color = c.soft, fontSize = 16.sp, lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { BigButton(c, "닫기", quiet = true) { confirmInstall = false } }
                        Box(Modifier.weight(2f)) {
                            BigButton(c, "설치하기") {
                                confirmInstall = false
                                Repo.install(context)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoRow(c: Palette, label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.soft, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun BigButton(c: Palette, label: String, enabled: Boolean = true, quiet: Boolean = false, onClick: () -> Unit) {
        val ground = when {
            quiet -> c.quiet
            enabled -> c.accent
            else -> c.accent.copy(alpha = 0.4f)
        }
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(ground).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (quiet) c.ink else c.onAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun SmallAction(c: Palette, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        Text(
            label, color = c.soft, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    private fun importZip(uri: Uri) {
        Repo.state.update { it.copy(message = null, checking = true) }
        Repo.scope.launch(Dispatchers.IO) {
            val target = File(Repo.romDir(this@MainActivity), "picked.zip")
            val copied = runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                Core.locatePayload(target)
            }
            if (copied.isSuccess) {
                Repo.state.update { it.copy(checking = false, zipPath = target.absolutePath) }
            } else {
                target.delete()
                Repo.state.update { it.copy(checking = false, message = copied.exceptionOrNull()?.message ?: "파일을 읽지 못했어요") }
            }
        }
    }
}
