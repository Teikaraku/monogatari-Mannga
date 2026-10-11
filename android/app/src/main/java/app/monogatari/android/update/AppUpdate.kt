package app.monogatari.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.monogatari.android.BuildConfig
import app.monogatari.android.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One platform's entry in latest.json. A build below [minBuild] must update before the app can be used. */
data class AppRelease(val version: String, val build: Int, val minBuild: Int, val urls: List<String>, val size: Long, val sha256: String, val notes: String) {
    fun forced(current: Int) = current < minBuild
}

object AppUpdateFeed {
    /** The project's own server first; GitHub serves the same file from its newest release. */
    val addresses = listOf("https://manga.monogatari.top/app-update/latest.json",
        "https://github.com/Teikaraku/monogatari-reader/releases/latest/download/latest.json")

    fun parse(json: JSONObject, platform: String): AppRelease? {
        val entry = json.optJSONObject(platform) ?: return null
        val build = entry.optInt("build")
        val urls = (listOf(entry.optString("url")) + entry.optJSONArray("mirrors").let { mirrors ->
            (0 until (mirrors?.length() ?: 0)).map { mirrors!!.optString(it) }
        }).filter { it.startsWith("https://") }.distinct()
        if (build <= 0 || urls.isEmpty()) return null
        return AppRelease(entry.optString("version"), build, entry.optInt("minBuild"), urls, entry.optLong("size"),
            entry.optString("sha256").lowercase(), entry.optString("notes"))
    }

    /** The newest release for [platform], or null when the feed lists none. */
    fun fetch(http: OkHttpClient, platform: String, addresses: List<String> = this.addresses): AppRelease? {
        val failures = mutableListOf<String>()
        for (address in addresses) {
            try {
                val request = Request.Builder().url(address).header("Cache-Control", "no-cache").build()
                return http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    parse(JSONObject(response.body?.string().orEmpty()), platform)
                }
            } catch (error: Exception) {
                failures += "${address.toHttpUrl().host}: ${error.message}"
            }
        }
        throw IOException(failures.joinToString("\n"))
    }
}

/** Finds a newer build, downloads it and hands it to the system installer, which updates in place and keeps the library. */
class AppUpdateController(private val repo: AppRepository, private val scope: CoroutineScope, private val report: (String) -> Unit) {
    var release by mutableStateOf<AppRelease?>(null)
        private set
    var forced by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf<File?>(null)
        private set
    private var checked = false

    /** Runs once per launch on its own; a check from the About page also says when the app is current. */
    fun check(manual: Boolean = false) {
        if (!manual && checked) return
        checked = true
        scope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { AppUpdateFeed.fetch(repo.client.http, "android") }
            } catch (error: Exception) {
                if (manual) report(text("Couldn't check for updates. Try again later.", "检查更新失败，请稍后重试。"))
                return@launch
            }
            val current = BuildConfig.VERSION_CODE
            if (latest == null || latest.build <= current) {
                if (manual) report(text("Monogatari is up to date.", "已是最新版本"))
                return@launch
            }
            val mustUpdate = latest.forced(current)
            if (!manual && !mustUpdate && repo.settings.int(SKIPPED_BUILD) == latest.build) return@launch
            if (release?.build != latest.build) { ready = null; failure = null }
            forced = mustUpdate
            release = latest
        }
    }

    fun later() { if (!forced && progress == null) release = null }

    fun skip() {
        val skipped = release ?: return
        if (forced || progress != null) return
        release = null
        scope.launch { repo.settings.put(SKIPPED_BUILD, skipped.build) }
    }

    fun update(context: Context) {
        val target = release ?: return
        ready?.let { install(context, it); return }
        if (progress != null) return
        progress = 0f
        failure = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(target) }
                ready = file
                install(context, file)
            } catch (error: Exception) {
                failure = error.message
            } finally {
                progress = null
            }
        }
    }

    private fun download(target: AppRelease): File {
        val directory = File(repo.context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "Monogatari-${target.build}.apk")
        directory.listFiles().orEmpty().filter { it.name != file.name }.forEach { it.delete() }
        if (file.isFile && verified(file, target)) return file
        val http = repo.client.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        val failures = mutableListOf<String>()
        for (url in target.urls) {
            val partial = File(directory, "${file.name}.part")
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("HTTP ${response.code}")
                    val total = body.contentLength().takeIf { it > 0 } ?: target.size
                    partial.outputStream().use { output -> body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != shown) { shown = percent; progress = percent / 100f }
                        }
                    } }
                }
                if (!verified(partial, target)) throw IOException(text("The download is damaged.", "安装包校验失败"))
                if (!partial.renameTo(file)) throw IOException(text("The download couldn't be saved.", "安装包保存失败"))
                return file
            } catch (error: Exception) {
                partial.delete()
                failures += "${url.toHttpUrl().host}: ${error.message}"
            }
        }
        throw IOException(text("Download failed. Try again later.", "下载失败，请稍后重试。") + "\n" + failures.joinToString("\n"))
    }

    private fun verified(file: File, target: AppRelease): Boolean =
        (target.size <= 0 || file.length() == target.size) && (target.sha256.isBlank() || sha256(file) == target.sha256)

    private fun install(context: Context, file: File) {
        if (context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName != context.packageName) {
            file.delete()
            ready = null
            failure = text("This package isn't Monogatari.", "安装包与物语不符")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            failure = text("Allow Monogatari to install apps, then tap Install.", "请允许物语安装应用，然后点“安装”。")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        failure = null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun text(english: String, chinese: String) =
        if (repo.context.resources.configuration.locales[0].language == "zh") chinese else english

    companion object {
        const val SKIPPED_BUILD = "Update.skippedBuild"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
