package app.monogatari.android.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.BackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CompleteBackupManager(val repo: AppRepository) {
    val directory = File(repo.context.filesDir, "backups").apply { mkdirs() }
    private val mutable = MutableStateFlow<List<BackupSummary>>(emptyList())
    val backups = mutable.asStateFlow()
    private val invalidMutable = MutableStateFlow<Map<File, String>>(emptyMap())
    val invalid = invalidMutable.asStateFlow()
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val valid = mutableListOf<BackupSummary>(); val errors = linkedMapOf<File, String>()
        directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith('.') && !it.name.endsWith(".bak") }.forEach { file ->
            try { valid += BackupCodec.preview(file) } catch (e: Exception) { errors[file] = e.message ?: "Corrupted backup" }
        }
        mutable.value = valid.sortedByDescending { it.date }; invalidMutable.value = errors
    }
    suspend fun create(name: String, options: BackupOptions, format: BackupFormat = BackupFormat.ANDROID_ZIP, automatic: Boolean = false): BackupSummary = operation.withLock {
        withContext(Dispatchers.IO) {
            val target = File(directory, "${UUID.randomUUID()}.${format.extension}"); val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try { BackupCodec.write(repo, stream, format, options, name, automatic); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); target.delete(); throw e }
            refresh(); BackupCodec.preview(target)
        }
    }
    suspend fun import(uri: Uri): BackupSummary = operation.withLock { withContext(Dispatchers.IO) {
        val temporary = File(directory, ".import-${UUID.randomUUID()}")
        try {
            repo.context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { PortableFiles.copy(input, it, 32L * 1024 * 1024 * 1024) } } ?: error("无法打开备份")
            val summary = BackupCodec.preview(temporary); val target = File(directory, "${UUID.randomUUID()}.${summary.format.extension}")
            check(temporary.renameTo(target)) { "无法保存备份" }; refresh(); summary.copy(file = target)
        } finally { temporary.delete() }
    } }
    suspend fun delete(file: File) = operation.withLock { withContext(Dispatchers.IO) { owned(file); check(file.delete()); refresh() } }
    suspend fun rename(file: File, name: String) = operation.withLock { withContext(Dispatchers.IO) {
        owned(file); require(name.isNotBlank()); val atomic = AtomicFile(file); val temporary = File(directory, ".rename-${UUID.randomUUID()}")
        try {
            val format = BackupCodec.preview(file).format
            if (format == BackupFormat.ANDROID_ZIP) ZipFile(file).use { input -> ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                input.entries().asSequence().forEach { entry -> output.putNextEntry(ZipEntry(entry.name)); input.getInputStream(entry).use { stream ->
                    if (entry.name == "manifest.json") {
                        val bytes = ByteArrayOutputStream(); PortableFiles.copy(stream, bytes, 64L * 1024 * 1024)
                        val manifest = JSONObject(bytes.toString("UTF-8")).put("name", name); manifest.optJSONObject("iosMetadata")?.put("name", name)
                        output.write(manifest.toString().toByteArray())
                    } else PortableFiles.copy(stream, output, 8L * 1024 * 1024 * 1024)
                }; output.closeEntry() }
            } } else {
                val bytes = ByteArrayOutputStream(); file.inputStream().use { PortableFiles.copy(it, bytes, 64L * 1024 * 1024) }
                val data = IOSBackupCodec.decode(bytes.toByteArray()).put("name", name)
                temporary.outputStream().use { if (format == BackupFormat.IOS_BINARY) IOSBackupCodec.writeBinary(data, it) else it.write(data.toString(2).toByteArray()) }
            }
            val stream = atomic.startWrite(); try { temporary.inputStream().use { PortableFiles.copy(it, stream, 32L * 1024 * 1024 * 1024) }; atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
            refresh()
        } finally { temporary.delete() }
    } }
    suspend fun export(file: File, uri: Uri) = withContext(Dispatchers.IO) { owned(file); repo.context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { PortableFiles.copy(it, output, 32L * 1024 * 1024 * 1024) } } ?: error("无法导出备份") }
    private fun owned(file: File) { require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile) }
    suspend fun automaticIfDue(now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        val settings = repo.settings; if (!settings.bool("AutomaticBackups.enabled")) return false
        val interval = intervals[settings.string("AutomaticBackups.interval")] ?: 86400L
        if (!force && now / 1000.0 - settings.double("AutomaticBackups.lastBackup") < interval) return false
        // A single worker and foreground request cannot create duplicate due backups.
        return automaticOperation.withLock {
            if (!force && now / 1000.0 - settings.double("AutomaticBackups.lastBackup") < interval) return@withLock false
            val options = BackupOptions(BackupSection.entries.filter { section -> autoKey(section)?.let(settings::bool) ?: settings.value("Android.Backups.${section.key}") != false }.toSet(), settings.bool("AutomaticBackups.sensitiveSettings"))
            create("", options, automatic = true)
            settings.put("AutomaticBackups.lastBackup", now / 1000.0)
            refresh(); mutable.value.filter { it.automatic }.drop(4).forEach { delete(it.file) }; true
        }
    }
    companion object {
        private val operation = Mutex(); private val automaticOperation = Mutex()
        val intervals = linkedMapOf("6hours" to 21600L, "12hours" to 43200L, "daily" to 86400L, "2days" to 172800L, "weekly" to 604800L)
        fun autoKey(section: BackupSection): String? = when (section) {
            BackupSection.LIBRARY -> "AutomaticBackups.libraryEntries"; BackupSection.TRACKING -> "AutomaticBackups.tracking"
            BackupSection.HISTORY -> "AutomaticBackups.history"; BackupSection.CHAPTERS -> "AutomaticBackups.chapters"
            BackupSection.SESSIONS -> "AutomaticBackups.readingSessions"; BackupSection.VOCABULARY -> "AutomaticBackups.vocabulary"
            BackupSection.UPDATES -> "AutomaticBackups.updates"; BackupSection.CATEGORIES -> "AutomaticBackups.categories"
            BackupSection.SETTINGS -> "AutomaticBackups.settings"; BackupSection.SOURCE_LISTS -> "AutomaticBackups.sourceLists"; else -> null
        }
        fun schedule(repo: AppRepository) {
            val work = WorkManager.getInstance(repo.context)
            if (!repo.settings.bool("AutomaticBackups.enabled")) { work.cancelUniqueWork("complete-backups"); return }
            val request = PeriodicWorkRequestBuilder<CompleteBackupWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork("complete-backups", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
class CompleteBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try { CompleteBackupManager(AppRepository.get(applicationContext)).automaticIfDue(); Result.success() } catch (e: Exception) { Result.retry() }
}
