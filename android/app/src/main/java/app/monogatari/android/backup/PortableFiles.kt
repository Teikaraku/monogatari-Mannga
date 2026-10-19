package app.monogatari.android.backup

import android.content.Context
import android.net.Uri
import app.monogatari.android.data.AppRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class PortableFiles(private val context: Context) {
    val files = linkedMapOf<String, File>()
    val originalUris = JSONObject()
    private val root = context.filesDir.canonicalFile
    fun add(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.isFile) { "备份文件不存在：${file.name}" }
        val path = when {
            canonical.toPath().startsWith(root.toPath()) -> {
                var parts = canonical.relativeTo(root).invariantSeparatorsPath.split('/')
                while (parts.size >= 4 && parts[0] == "restored" && parts[2] == "files") parts = parts.drop(3)
                "files/" + parts.joinToString("/")
            }
            canonical.toPath().startsWith(context.cacheDir.canonicalFile.toPath()) -> "files/cache/" + canonical.relativeTo(context.cacheDir.canonicalFile).invariantSeparatorsPath
            else -> error("备份引用了应用目录外的文件")
        }
        require(!path.contains("../") && (files[path] == null || files[path] == canonical)) { "备份资源路径冲突" }; files[path] = canonical
        originalUris.put(Uri.fromFile(canonical).toString(), path).put(Uri.fromFile(file.absoluteFile).toString(), path)
        // Android exposes the same private directory through /data/user/0 and /data/data aliases.
        // EPUB HTML/CSS retains the original absolute URL, including the alias used by its importer.
        listOf(context.filesDir.absoluteFile, context.cacheDir.absoluteFile).forEach { aliasRoot ->
            val resolvedRoot = aliasRoot.canonicalFile
            if (canonical.toPath().startsWith(resolvedRoot.toPath())) {
                val aliasFile = File(aliasRoot, canonical.relativeTo(resolvedRoot).invariantSeparatorsPath)
                originalUris.put(Uri.fromFile(aliasFile).toString(), path)
            }
        }
        return "backup://$path"
    }
    fun directory(directory: File) { require(directory.canonicalFile.toPath().startsWith(root.toPath())); if (directory.exists()) directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".writing") }.forEach(::add) }
    fun portable(value: String, epub: Boolean = false): String {
        if (value.startsWith("aidoku-archive://")) {
            val uri = Uri.parse(value); val id = uri.path.orEmpty().trimStart('/')
            require(uri.host == "download" && id.matches(Regex("[a-f0-9]{64}"))) { "下载归档引用无效" }
            val archive = File(root, "downloads/$id.cbz"); add(archive)
            ZipFile(archive).use { require(it.getEntry(uri.getQueryParameter("entry")) != null) { "下载归档缺少页面" } }
            return value
        }
        if (!value.startsWith("file:")) return value
        if (value.startsWith("file:///android_asset/")) return value
        val file = File(Uri.parse(value).path ?: error("文件 URL 无效"))
        if (epub && file.canonicalFile.toPath().startsWith(root.toPath())) {
            val pieces = file.canonicalFile.relativeTo(root).invariantSeparatorsPath.split('/')
            val local = pieces.indexOf("local")
            if (local >= 0 && pieces.size > local + 2) directory(File(root, pieces.take(local + 2).joinToString("/"))) else directory(file.parentFile!!)
        }
        val portable = add(file); originalUris.put(value, portable.removePrefix("backup://")); return portable
    }
    fun absolute(value: String): String = if (value.isBlank()) value else portable(if (value.startsWith("file:")) value else Uri.fromFile(File(value)).toString())
    fun collectInline(text: String) { Regex("file://[^\\s\"'<>)]*").findAll(text).forEach { match -> portable(match.value) } }
    fun manifest() = JSONObject().put("filesRoot", Uri.fromFile(root).toString().trimEnd('/') + "/").put("originalUris", originalUris)
    fun write(zip: ZipOutputStream): JSONArray = JSONArray().also { manifest ->
        files.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name)); val digest = MessageDigest.getInstance("SHA-256")
            val size = file.inputStream().use { input -> copy(input, zip, 8L * 1024 * 1024 * 1024, digest) }; zip.closeEntry()
            manifest.put(JSONObject().put("name", name).put("size", size).put("sha256", digest.digest().hex()))
        }
    }
    companion object {
        fun copy(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "备份文件超过大小限制" }; output.write(buffer, 0, n); digest?.update(buffer, 0, n) }; return total
        }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

/** All untrusted entries are extracted and checked before any live data or file is changed. */
class RestoreFiles(private val context: Context) : AutoCloseable {
    val staging = File(context.filesDir, ".restore-stage-${UUID.randomUUID()}").apply { mkdirs() }
    val destination = File(context.filesDir, "restored/${UUID.randomUUID()}")
    val metadata = linkedMapOf<String, JSONObject>()
    private val seen = linkedMapOf<String, Pair<Long, String>>()
    private val previous = mutableListOf<Pair<File, File?>>()
    private var moved = false
    private var finished = false
    var selected: Set<BackupSection> = BackupSection.entries.toSet()
    fun extract(input: InputStream) {
        var count = 0; var total = 0L
        ZipInputStream(input.buffered()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            require(++count <= 100000 && !entry.name.startsWith('/') && !entry.name.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(entry.name)) { "备份文件路径无效" }
            val file = File(staging, entry.name).canonicalFile
            require(file.toPath().startsWith(staging.canonicalFile.toPath()) && file != staging.canonicalFile && entry.name !in seen) { "备份包含越界或重复文件" }
            if (entry.isDirectory) { file.mkdirs(); zip.closeEntry(); continue }
            require(entry.name.startsWith("files/") || entry.name in setOf("state.json", "manifest.json", "tracking.json", "dictionary.json", "reader-options.json", "source-settings.json", "credentials.json", "dictionary.db")) { "未知备份容器条目：${entry.name}" }
            file.parentFile?.mkdirs(); val digest = MessageDigest.getInstance("SHA-256")
            val size = file.outputStream().use { PortableFiles.copy(zip, it, if (entry.name.endsWith(".json") && !entry.name.startsWith("files/")) 64L * 1024 * 1024 else 8L * 1024 * 1024 * 1024, digest) }
            total += size; require(total <= 32L * 1024 * 1024 * 1024) { "备份解压总量超过限制" }
            seen[entry.name] = size to digest.digest().joinToString("") { "%02x".format(it) }
            if (!entry.name.startsWith("files/") && entry.name.endsWith(".json")) metadata[entry.name] = JSONObject(file.readText())
            zip.closeEntry()
        } }
        require(metadata.containsKey("state.json")) { "备份缺少 state.json" }
        metadata["manifest.json"]?.optJSONArray("files")?.let { files -> val declared = mutableSetOf<String>(); repeat(files.length()) { i ->
            val item = files.getJSONObject(i); val actual = seen[item.getString("name")] ?: error("备份缺少文件")
            require(declared.add(item.getString("name"))) { "备份校验清单含重复条目" }
            require(actual.first == item.getLong("size") && actual.second == item.getString("sha256")) { "备份文件校验失败" }
        }; require(declared == seen.keys.filter { it.startsWith("files/") }.toSet()) { "备份文件校验清单不完整" } }
        validatePayloads()
    }
    fun stageSource(input: InputStream, expectedId: String): app.monogatari.android.sources.InstalledSource {
        val temporary = File(staging, ".source-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var count = 0; var total = 0L; val names = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break; require(++count <= 4096 && names.add(entry.name) && !entry.name.contains('\\') && !entry.name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(entry.name))
                val file = File(temporary, entry.name).canonicalFile; require(file.toPath().startsWith(temporary.canonicalFile.toPath()) && file != temporary.canonicalFile)
                if (entry.isDirectory) file.mkdirs() else { file.parentFile?.mkdirs(); total += file.outputStream().use { PortableFiles.copy(zip, it, 50L * 1024 * 1024) }; require(total <= 100L * 1024 * 1024) }; zip.closeEntry()
            } }
            val source = app.monogatari.android.sources.SourceRuntimeManager.get(context).validateDirectory(temporary, expectedId)
            val target = File(staging, "files/sources/${AppRepository.hash(expectedId)}"); require(!target.exists()); target.parentFile.mkdirs(); check(source.directory.renameTo(target))
            target.walkTopDown().filter { it.isFile }.forEach { file ->
                val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                seen[file.relativeTo(staging).invariantSeparatorsPath] = file.length() to digest.digest().joinToString("") { "%02x".format(it) }
            }
            return source.copy(directory = target)
        } finally { temporary.deleteRecursively() }
    }
    private fun rooted(path: String): Boolean = when {
        path.startsWith("files/sources/") -> BackupSection.SOURCES in selected
        path.startsWith("files/dictionaries/") -> BackupSection.DICTIONARIES in selected
        path.startsWith("files/downloads/") && path.endsWith(".cbz") || path.startsWith("files/models/custom-") || path.startsWith("files/source-pages-") -> BackupSection.MEDIA in selected
        else -> false
    }
    fun target(name: String): File { require(name.startsWith("files/") && seen.containsKey(name)) { "备份引用缺少文件：$name" }; return if (rooted(name)) File(context.filesDir, name.removePrefix("files/")) else File(destination, name) }
    fun restore(value: String, plainPath: Boolean = false): String {
        if (!value.startsWith("backup://")) {
            if (value.startsWith("file:") && !value.startsWith("file:///android_asset/")) {
                val mapping = metadata["manifest.json"]?.optJSONObject("originalUris")?.optString(value)?.takeIf { it.isNotEmpty() }
                require(mapping != null) { "备份包含未打包的绝对文件引用" }; return restore("backup://$mapping", plainPath)
            }
            if (value.startsWith("aidoku-archive://")) { val uri = Uri.parse(value); val id = uri.path.orEmpty().trimStart('/'); val name = "files/downloads/$id.cbz"; require(id.matches(Regex("[a-f0-9]{64}")) && seen.containsKey(name) && uri.host == "download") { "备份缺少下载归档" }; ZipFile(File(staging, name)).use { require(!uri.getQueryParameter("entry").isNullOrBlank() && it.getEntry(uri.getQueryParameter("entry")) != null) { "备份下载归档缺少页面" } } }
            return value
        }
        val file = target(value.removePrefix("backup://")); return if (plainPath) file.path else Uri.fromFile(file).toString()
    }
    fun rewrite(text: String): String {
        var rewritten = text
        metadata["manifest.json"]?.optJSONObject("originalUris")?.let { mapping -> mapping.keys().asSequence().toList().sortedByDescending { it.length }.forEach { old ->
            val name = mapping.getString(old); rewritten = rewritten.replace(old, Uri.fromFile(target(name)).toString())
        } }
        return rewritten
    }
    private fun validatePayloads() {
        staging.walkTopDown().filter { it.isFile && it.name.endsWith(".cbz") && it.relativeTo(staging).invariantSeparatorsPath.startsWith("files/downloads/") }.forEach { file ->
            require(file.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))) { "下载归档名称无效" }; ZipFile(file).use { zip -> require(zip.size() > 0) { "下载归档为空" } }
        }
        val packages = File(staging, "files/sources")
        packages.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val source = app.monogatari.android.sources.SourceRuntimeManager.get(context).validateDirectory(dir)
            require(source.directory.canonicalFile == dir.canonicalFile && dir.name == AppRepository.hash(source.id)) { "源包元数据不匹配" }
        }
    }
    fun commit(journal: JSONObject? = null) {
        require(!moved); destination.parentFile?.mkdirs()
        // EPUB references are rewritten while files are still staged.
        staging.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("html", "xhtml", "htm", "css", "svg", "json") && it.relativeTo(staging).invariantSeparatorsPath.startsWith("files/") }.forEach { file -> require(file.length() <= 64L * 1024 * 1024); file.writeText(rewrite(file.readText())) }
        val rootedEntries = seen.keys.filter(::rooted).map { name ->
            when {
                name.startsWith("files/sources/") -> name.split('/').take(3).joinToString("/")
                name.startsWith("files/dictionaries/") -> name.split('/').take(3).joinToString("/")
                else -> name
            }
        }.distinct()
        journal?.let { it.put("keepMedia", BackupSection.MEDIA in selected); DataRestoreJournal.planFiles(context, it, staging, destination, rootedEntries) }
        check(staging.renameTo(destination)) { "无法提交备份文件" }; moved = true
        for (name in rootedEntries) {
            val incoming = File(destination, name); val live = File(context.filesDir, name.removePrefix("files/")); val old = if (live.exists()) File(destination, ".previous/${name.removePrefix("files/")}").apply { parentFile?.mkdirs(); check(live.renameTo(this)) { "无法暂存原文件" } } else null
            previous += live to old; live.parentFile?.mkdirs(); check(incoming.renameTo(live)) { "无法提交下载或源包" }
        }
    }
    fun finish() {
        // The SQLite commit marker is already durable; cleanup must never trigger an undo.
        finished = true
        File(destination, ".previous").deleteRecursively()
        destination.listFiles().orEmpty().filter { it.name != "files" }.forEach { it.deleteRecursively() }
        // Package payloads have been switched to their canonical roots. Unselected payloads are discarded.
        File(destination, "files/sources").deleteRecursively(); File(destination, "files/dictionaries").deleteRecursively()
        if (BackupSection.MEDIA !in selected) File(destination, "files").deleteRecursively()
        finished = true
    }
    fun rollback() {
        previous.asReversed().forEach { (live, old) -> live.deleteRecursively(); if (old != null && old.exists()) check(old.renameTo(live)) { "无法回滚原文件" } }; previous.clear()
        if (moved) destination.deleteRecursively()
    }
    override fun close() { if (!finished) rollback(); staging.deleteRecursively() }
}
