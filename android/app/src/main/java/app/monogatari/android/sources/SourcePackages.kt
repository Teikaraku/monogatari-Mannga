package app.monogatari.android.sources

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.monogatari.android.data.SourceConfig
import app.monogatari.android.data.SecretStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class InstalledSource(val directory: File, val manifest: JSONObject, val origin: String = "", val previousVersion: Int? = null, val rollbackDirectory: File? = null) {
    val info: JSONObject get() = manifest.optJSONObject("info") ?: manifest
    val id: String get() = info.getString("id")
    val name: String get() = info.getString("name")
    val version: Int get() = info.optInt("version")
    val baseUrl: String get() = info.optString("url").ifBlank { info.optJSONArray("urls")?.optString(0).orEmpty() }
    val config: SourceConfig get() = SourceConfig(id, name, "wasm", baseUrl, manifest.toString())
    val icon: String get() = Uri.fromFile(File(directory, "icon.png")).toString()
    fun jsonArray(name: String): JSONArray = File(directory, "$name.json").takeIf { it.isFile }?.let { JSONArray(it.readText()) }
        ?: manifest.optJSONArray(name) ?: JSONArray()
}

/** Source packages are untrusted ZIPs. Extract to staging and atomically switch only after validation. */
class SourceRuntimeManager(val context: Context, private val http: OkHttpClient = OkHttpClient()) {
    private val root = File(context.filesDir, "sources").apply { mkdirs() }
    private val runtimes = mutableMapOf<String, WasmSourceRuntime>()
    fun directory(id: String): File = File(root, digest(id))
    @Synchronized fun installed(): List<InstalledSource> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { directory -> runCatching { InstalledSource(directory, JSONObject(File(directory, "source.json").readText()), File(directory, "origin.txt").takeIf { it.exists() }?.readText().orEmpty()) }.getOrNull() }.sortedBy { it.name }
    @Synchronized fun installed(id: String): InstalledSource = installed().firstOrNull { it.id == id } ?: error("源包未安装：$id")
    @Synchronized fun runtime(id: String): WasmSourceRuntime = runtimes.getOrPut(id) { WasmSourceRuntime(context, installed(id), http) }
    @Synchronized fun invalidate(id: String) { runtimes.remove(id)?.close() }
    @Synchronized fun remove(id: String) { invalidate(id); val target = directory(id).canonicalFile; require(target.parentFile == root.canonicalFile); target.deleteRecursively() }
    private fun updateJournal(id:String)=AtomicFile(File(root,".update-${digest(id)}.json"))
    private fun writeUpdateJournal(id:String,previous:File,version:Int) {
        val file=updateJournal(id);val output=file.startWrite()
        try { output.write(JSONObject().put("id",id).put("previous",previous.name).put("version",version).toString().toByteArray());file.finishWrite(output) }
        catch(error:Exception) { file.failWrite(output);throw error }
    }
    /** SQLite's committed source version decides which side of an interrupted package update wins. */
    @Synchronized fun recoverPendingInstalls(committedVersions:Map<String,Int>) {
        root.listFiles().orEmpty().filter { it.name.startsWith(".update-") && it.name.endsWith(".json") }.forEach { journal ->
            val record=JSONObject(AtomicFile(journal).readFully().toString(Charsets.UTF_8));val id=record.getString("id")
            require(journal.name==updateJournal(id).baseFile.name) { "Invalid source update journal" }
            val previous=File(root,record.getString("previous")).canonicalFile;val target=directory(id).canonicalFile
            require(previous.parentFile==root.canonicalFile && previous.name.startsWith(".previous-") && target.parentFile==root.canonicalFile)
            invalidate(id)
            if(previous.isDirectory) {
                if(target.isDirectory && committedVersions[id]==record.getInt("version")) check(previous.deleteRecursively())
                else { if(target.exists()) check(target.deleteRecursively());check(previous.renameTo(target)) { "Cannot restore interrupted source update" } }
            }
            AtomicFile(journal).delete()
        }
    }
    /** Read-only validation shared by installation and staged, transactional backup restores. */
    fun validateDirectory(directory: File, expectedId: String? = null, expectedVersion: Int? = null): InstalledSource {
        val rootPath = directory.canonicalFile.toPath()
        require(directory.isDirectory) { "源包目录不存在" }
        var entries = 0; var total = 0L
        val manifests = mutableListOf<File>()
        directory.walkTopDown().onEnter { child ->
            require(!java.nio.file.Files.isSymbolicLink(child.toPath()) && child.canonicalFile.toPath().startsWith(rootPath)) { "源包包含越界路径" }; true
        }.forEach { file ->
            require(++entries <= 4097) { "源包文件过多" }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(rootPath)) { "源包包含越界路径" }
            if (file.isFile) { total += file.length(); require(file.length() <= 50L * 1024 * 1024 && total <= 100L * 1024 * 1024) { "源包过大" }; if (file.name == "source.json") manifests += file }
        }
        require(manifests.size == 1 && manifests.single().length() <= 2 * 1024 * 1024) { "源包必须包含唯一 source.json" }
        val manifest = JSONObject(manifests.single().readText()); val payload = manifests.single().parentFile!!
        val candidate = InstalledSource(payload, manifest, File(payload, "origin.txt").takeIf { it.isFile }?.readText().orEmpty())
        require(candidate.id.isNotBlank() && candidate.name.isNotBlank()) { "漫画源元数据缺失" }
        require(expectedId == null || candidate.id == expectedId) { "源包 ID 与源列表不符" }
        require(expectedVersion == null || candidate.version == expectedVersion) { "源包版本与源列表不符" }
        for (name in listOf("settings", "filters")) candidate.jsonArray(name)
        val wasm = File(payload, "main.wasm"); require(wasm.isFile) { "缺少 main.wasm" }
        WasmSourceRuntime.validateModule(wasm.readBytes())
        return candidate
    }
    fun install(address: String, expectedId: String? = null, expectedVersion: Int? = null, retainPrevious: Boolean = false): InstalledSource {
        val response = http.newCall(Request.Builder().url(address).build()).execute()
        return response.use { require(it.isSuccessful) { "源包下载失败：HTTP ${it.code}" }; val body = it.body ?: error("源包为空"); body.byteStream().use { stream -> install(stream, address, expectedId, expectedVersion, retainPrevious) } }
    }
    fun install(uri: Uri, retainPrevious: Boolean = false): InstalledSource = context.contentResolver.openInputStream(uri)?.use { install(it, retainPrevious = retainPrevious) } ?: error("无法读取源包")
    @Synchronized fun commitInstall(source: InstalledSource) { source.rollbackDirectory?.let { previous -> require(previous.canonicalFile.parentFile == root.canonicalFile && previous.name.startsWith(".previous-")); check(!previous.exists() || previous.deleteRecursively()); updateJournal(source.id).delete() } }
    @Synchronized fun rollbackInstall(source: InstalledSource) { source.rollbackDirectory?.let { previous ->
        require(previous.canonicalFile.parentFile == root.canonicalFile && previous.name.startsWith(".previous-")); check(previous.isDirectory)
        val target = directory(source.id).canonicalFile; require(target.parentFile == root.canonicalFile); invalidate(source.id); check(target.deleteRecursively()); check(previous.renameTo(target)) { "无法恢复旧漫画源" };updateJournal(source.id).delete()
    } }
    @Synchronized fun install(input: InputStream, origin: String = "", expectedId: String? = null, expectedVersion: Int? = null, retainPrevious: Boolean = false): InstalledSource {
        val staging = File(root, ".staging-${UUID.randomUUID()}").apply { mkdirs() }
        var oldDirectory: File? = null
        var installedTarget: File? = null
        try {
            var total = 0L; var entries = 0
            ZipInputStream(input).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 4096) { "源包文件过多" }
                require(!entry.name.contains('\\') && !entry.name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(entry.name)) { "源包路径无效" }
                val file = File(staging, entry.name).canonicalFile
                require(file.toPath().startsWith(staging.canonicalFile.toPath()) && file != staging.canonicalFile) { "源包包含越界路径" }
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs(); file.outputStream().use { out ->
                        val buffer = ByteArray(32 * 1024); var fileTotal = 0L
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; fileTotal += n; require(total <= 100L * 1024 * 1024 && fileTotal <= 50L * 1024 * 1024) { "源包过大" }; out.write(buffer, 0, n) }
                    }
                }; zip.closeEntry()
            } }
            val candidate = validateDirectory(staging, expectedId, expectedVersion)
            val payload = candidate.directory; val manifest = candidate.manifest
            if (origin.isNotBlank()) File(payload, "origin.txt").writeText(origin)
            val target = directory(candidate.id)
            installedTarget = target
            val previousVersion = if (target.exists()) runCatching { InstalledSource(target, JSONObject(File(target, "source.json").readText())).version }.getOrNull() else null
            if (target.exists()) { oldDirectory = File(root, ".previous-${UUID.randomUUID()}" ); if(retainPrevious)writeUpdateJournal(candidate.id,oldDirectory!!,candidate.version); check(target.renameTo(oldDirectory!!)) { "无法备份现有源包" } }
            if (!payload.renameTo(target)) { oldDirectory?.renameTo(target); error("无法安装源包") }
            invalidate(candidate.id); val rollback = if (retainPrevious) oldDirectory else null
            if (!retainPrevious) oldDirectory?.deleteRecursively(); oldDirectory = null
            return InstalledSource(target, manifest, origin, previousVersion, rollback)
        } finally { staging.deleteRecursively(); oldDirectory?.let { previous -> if (previous.exists() && installedTarget?.exists() == false) previous.renameTo(installedTarget!!) } }
    }
    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        @Volatile private var shared: SourceRuntimeManager? = null
        fun get(context: Context): SourceRuntimeManager {
            val application = context.applicationContext
            return shared?.takeIf { it.context === application } ?: synchronized(this) {
                shared?.takeIf { it.context === application } ?: SourceRuntimeManager(application).also { shared = it }
            }
        }
    }
}

/** Source settings are encrypted, scoped to a source and available to defaults imports and UI. */
interface SourcePreferenceBackend {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String> = emptySet()
}
class SourcePreferences(context: Context, private val sourceId: String, backend: SourcePreferenceBackend? = null) {
    private val store: SourcePreferenceBackend = backend ?: object : SourcePreferenceBackend {
        val secrets = SecretStore(context)
        override fun get(key: String) = secrets.get(key)
        override fun set(key: String, value: String) = secrets.set(key, value)
        override fun remove(key: String) = secrets.remove(key)
        override fun keys(): Set<String> = context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.keys
    }
    private fun name(key: String) = "source:$sourceId:$key"
    @Synchronized fun get(key: String): Any? = store.get(name(key))?.let { val j = JSONObject(it); when (j.optString("kind")) {
        "float" -> (j.value("value") as Number).toFloat()
        "data" -> android.util.Base64.decode(j.getString("value"), android.util.Base64.NO_WRAP)
        else -> j.value("value")
    } }
    @Synchronized fun set(key: String, value: Any?) { if (value == null) store.remove(name(key)) else store.set(name(key), JSONObject().put("kind", when (value) { is Float, is Double -> "float"; is ByteArray -> "data"; else -> "value" }).put("value", if (value is ByteArray) android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP) else value).toString()) }
    @Synchronized fun keys(): Set<String> = store.keys().filter { it.startsWith(name("")) }.map { it.removePrefix(name("")) }.toSet()
    /** Encoded values retain Float/Data distinctions and include source-created defaults keys. */
    @Synchronized fun exportEncoded(include: (String) -> Boolean = { true }): JSONObject = JSONObject().also { result ->
        keys().filter(include).forEach { key -> store.get(name(key))?.let { result.put(key, JSONObject(it)) } }
    }
    @Synchronized fun importEncoded(values: JSONObject, replace: Boolean = false) {
        val validated = values.keys().asSequence().associateWith { key -> val entry = values.getJSONObject(key)
            require(entry.optString("kind") in setOf("float", "data", "value") && entry.has("value")) { "无效源设置备份" }
            when(entry.getString("kind")) { "float" -> require(entry.get("value") is Number); "data" -> android.util.Base64.decode(entry.getString("value"), android.util.Base64.NO_WRAP) }
            entry.toString()
        }
        if (replace) keys().forEach { store.remove(name(it)) }
        validated.forEach { (key, value) -> store.set(name(key), value) }
    }
    fun initialize(settings: JSONArray) { settings.values().filterIsInstance<JSONObject>().forEach { j ->
        val key = j.optString("key"); if (key.isNotEmpty() && get(key) == null && j.has("default")) set(key, j.value("default"))
        j.optJSONArray("items")?.let(::initialize)
    } }
}
