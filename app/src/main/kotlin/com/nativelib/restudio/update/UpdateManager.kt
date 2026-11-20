package com.nativelib.restudio.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.nativelib.restudio.core.common.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class UpdateInfo(
    val tag: String,
    val buildNumber: Int,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val installedBuild: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from this project's GitHub Releases. The only network traffic the app makes:
 * one request to the GitHub Releases API and, when the user accepts, the APK + SHA256SUMS download.
 * Imported files are never uploaded anywhere.
 */
class UpdateManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _autoCheck = MutableStateFlow(prefs.getBoolean("auto_check", true))
    val autoCheck: StateFlow<Boolean> = _autoCheck
    fun setAutoCheck(v: Boolean) { prefs.edit().putBoolean("auto_check", v).apply(); _autoCheck.value = v }

    val installedBuild: Long
        get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Automatic check at most every 6 hours; tags the user dismissed are not offered again automatically. */
    suspend fun autoCheckIfDue() {
        if (!_autoCheck.value) return
        val last = prefs.getLong("last_check", 0L)
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return
        check(manual = false)
    }

    suspend fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            _state.value = when {
                info == null || !UpdateLogic.isNewer(info.tag, installedBuild) -> if (manual) UpdateState.UpToDate(installedBuild) else UpdateState.Idle
                !manual && prefs.getString("dismissed_tag", null) == info.tag -> UpdateState.Idle
                else -> UpdateState.Available(info)
            }
        } catch (e: Exception) {
            _state.value = if (manual) UpdateState.Failed("Update check failed: ${e.message ?: e.javaClass.simpleName}") else UpdateState.Idle
        }
    }

    fun dismiss() {
        (_state.value as? UpdateState.Available)?.let { prefs.edit().putString("dismissed_tag", it.info.tag).apply() }
        _state.value = UpdateState.Idle
    }

    suspend fun download(info: UpdateInfo) {
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, info.apkName)
                httpDownload(info.apkUrl, out) { d, t -> _state.value = UpdateState.Downloading(info, d, if (t > 0) t else info.apkSize) }
                info.sumsUrl?.let { url ->
                    val expected = UpdateLogic.parseSha256Sums(httpGetText(url))[info.apkName]
                        ?: throw IOException("SHA256SUMS.txt has no entry for ${info.apkName}")
                    val actual = Hashing.sha256(out)
                    if (actual != expected) { out.delete(); throw IOException("SHA-256 mismatch — download discarded") }
                }
                out
            }
            _state.value = UpdateState.ReadyToInstall(info, file)
            install(file)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = UpdateState.Idle; throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Opens the system installer; on Android 8+ first asks for "install unknown apps" permission if needed. */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (_: ActivityNotFoundException) { }
            return // user returns and taps "Install" again
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    private fun fetchLatest(): UpdateInfo? {
        val json = JSONObject(httpGetText(UpdateLogic.LATEST_RELEASE_API))
        val tag = json.optString("tag_name")
        val build = UpdateLogic.buildNumberFromTag(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
        val apkName = UpdateLogic.chooseApk(byName.keys.toList(), context.packageName) ?: return null
        val apk = byName.getValue(apkName)
        return UpdateInfo(
            tag = tag, buildNumber = build, title = json.optString("name", tag), notes = json.optString("body"),
            apkName = apkName, apkUrl = apk.optString("browser_download_url"), apkSize = apk.optLong("size"),
            sumsUrl = byName["SHA256SUMS.txt"]?.optString("browser_download_url"),
        )
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
            c.setRequestProperty("User-Agent", "NativeLibREStudio-Updater")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                current = URL(current, loc)
                if (current.protocol != "https") throw IOException("Refusing non-HTTPS redirect")
                return@repeat
            }
            if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
            return c
        }
        throw IOException("Too many redirects")
    }

    private fun httpGetText(url: String): String = open(url).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }

    private suspend fun httpDownload(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (done - last > 256 * 1024) { onProgress(done, total); last = done }
                    }
                    onProgress(done, total)
                }
            }
        } finally { c.disconnect() }
    }
}
