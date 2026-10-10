package app.monogatari.android.reader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.monogatari.android.data.ArchiveImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CatalogUpscaleModel(val originalFile:String,val downloadUrl:String,val name:String,val type:String,val version:Int,val size:Long,
    val info:String,val tags:List<String>,val metadata:String) {
    val filename get()=Uri.parse(originalFile).lastPathSegment.orEmpty().removeSuffix(".zip")
    val stem get()=filename.substringBeforeLast('.')
    fun matches(model:UpscaleModel):Boolean=if(model.catalogFile.isNotBlank())Uri.parse(model.catalogFile).lastPathSegment?.removeSuffix(".zip")==filename else model.file.substringBeforeLast('.')==stem
    fun installed(models:List<UpscaleModel>)=models.filter { it.installed && matches(it) }.maxByOrNull { it.version }
    fun needsUpdate(models:List<UpscaleModel>)=installed(models)?.let { it.version<version } ?: true
}

/** Downloads original source bytes and metadata; source files are never presented as executable ONNX installations. */
class UpscaleModelCatalog(private val context:Context,private val http:OkHttpClient=OkHttpClient(),val url:String=OFFICIAL_URL) {
    private val root=File(context.cacheDir,"model-sources").apply { mkdirs() }
    private val catalog=AtomicFile(File(root,"catalog.json"))
    fun cached():List<CatalogUpscaleModel> = runCatching {
        val j=catalog.openRead().bufferedReader().use { JSONObject(it.readText()) };require(j.getString("url")==url)
        parse(j.getJSONObject("catalog"),url)
    }.getOrDefault(emptyList())
    suspend fun refresh():List<CatalogUpscaleModel> = withContext(Dispatchers.IO) {
        val j=http.newCall(Request.Builder().url(url).header("User-Agent","Monogatari/0.9").get().build()).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val body=response.body ?: error("模型目录为空")
            JSONObject(body.byteStream().use { input ->java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(input,it,1024*1024) }.toString(Charsets.UTF_8.name()) })
        }
        val parsed=parse(j,url);var output:java.io.FileOutputStream?=null
        try { output=catalog.startWrite();output.write(JSONObject().put("url",url).put("catalog",j).toString().toByteArray());catalog.finishWrite(output) }
        catch(error:Throwable) { output?.let(catalog::failWrite);throw error }
        parsed
    }
    fun downloaded(model:CatalogUpscaleModel):File?=target(model).takeIf { it.isFile && File(root,"${it.name}.json").isFile }
    private fun target(model:CatalogUpscaleModel):File {
        val key=MessageDigest.getInstance("SHA-256").digest("${model.downloadUrl}:${model.version}".toByteArray()).joinToString("") { "%02x".format(it) }
        val suffix=if(model.filename.endsWith(".mlpackage"))".mlpackage.zip" else ".mlmodel"
        return File(root,"$key$suffix")
    }
    suspend fun download(model:CatalogUpscaleModel,progress:(Long,Long)->Unit={_,_->}):File=withContext(Dispatchers.IO) {
        downloaded(model)?.let { return@withContext it }
        val file=target(model);val temporary=File(root,"${file.name}.writing")
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            http.newCall(Request.Builder().url(model.downloadUrl).header("User-Agent","Monogatari/0.9").get().build()).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" };val body=response.body ?: error("模型文件为空")
                require(body.contentLength()<=MAX_BYTES) { "模型文件超出 256 MiB 限制" }
                body.byteStream().use { input ->temporary.outputStream().use { output ->
                    val buffer=ByteArray(65536);var count=0L
                    while(true) { currentCoroutineContext().ensureActive();val read=input.read(buffer);if(read<0)break;count+=read;require(count<=MAX_BYTES) { "模型文件超出 256 MiB 限制" }
                        output.write(buffer,0,read);digest.update(buffer,0,read);progress(count,body.contentLength()) }
                    require(count>0) { "模型文件为空" }
                } }
            }
            val expected=JSONObject(model.metadata).optString("sha256").takeIf(String::isNotBlank)
            if(expected!=null)require(expected.equals(digest.digest().joinToString("") { "%02x".format(it) },true)) { "模型文件校验失败" }
            check(temporary.renameTo(file)) { "无法保存模型文件" }
            val sidecar=AtomicFile(File(root,"${file.name}.json"));var out:java.io.FileOutputStream?=null
            try { out=sidecar.startWrite();out.write(model.metadata.toByteArray());sidecar.finishWrite(out) }
            catch(error:Throwable) { out?.let(sidecar::failWrite);file.delete();throw error }
            file
        } finally { temporary.delete() }
    }
    suspend fun export(model:CatalogUpscaleModel,output:OutputStream)=withContext(Dispatchers.IO) {
        val file=downloaded(model) ?: error("请先下载原模型文件")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(if(model.filename.endsWith(".mlpackage"))"source.mlpackage.zip" else "source.mlmodel"));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
            zip.putNextEntry(ZipEntry("model.json"));zip.write(model.metadata.toByteArray());zip.closeEntry()
        }
    }
    companion object {
        const val OFFICIAL_URL="http://upscale.aidoku.app/models.json"
        const val MAX_BYTES=256L*1024*1024
        fun parse(catalog:JSONObject,baseUrl:String):List<CatalogUpscaleModel> {
            val base=baseUrl.toHttpUrl();val rows=catalog.getJSONArray("models");require(rows.length()<=1000) { "模型目录过大" }
            return (0 until rows.length()).mapNotNull { index ->
                val row=rows.getJSONObject(index);val type=row.optString("type")
                if(type !in setOf("image","multiarray"))return@mapNotNull null
                val original=row.getString("file");val resolved=requireNotNull(base.resolve(original)) { "无效模型地址" }
                require(resolved.scheme in setOf("http","https") && resolved.username.isEmpty() && resolved.password.isEmpty()) { "无效模型地址" }
                val filename=resolved.pathSegments.last();require(filename.endsWith(".mlmodel") || filename.endsWith(".mlpackage") || filename.endsWith(".mlpackage.zip")) { "无效 Core ML 模型文件" }
                val target=if(filename.endsWith(".mlpackage"))resolved.newBuilder().encodedPath(resolved.encodedPath+".zip").build() else resolved
                val version=row.optInt("version",1);require(version>0) { "无效模型版本" }
                CatalogUpscaleModel(original,target.toString(),row.optString("name").ifBlank { filename },type,version,row.optLong("size"),row.optString("info"),
                    row.optJSONArray("tags")?.let { a ->(0 until a.length()).map { a.getString(it) } }.orEmpty(),row.toString())
            }.also { require(it.map(CatalogUpscaleModel::originalFile).distinct().size==it.size) { "模型目录包含重复文件" } }
        }
    }
}
