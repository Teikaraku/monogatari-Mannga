package app.monogatari.android.reader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.ArchiveImporter
import app.monogatari.android.data.SettingsStore
import app.monogatari.android.data.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class UpscaleModel(val file: String, val name: String, val blockSize: Int, val shrinkSize: Int, val scale: Int,
    val overlap: Int, val size: Long, val sha256: String, val bundled: Boolean, val installed: Boolean,
    val inputHalfStep:Boolean=true,val preserveGrayscale:Boolean=true,val info:String="",val tags:List<String> = emptyList(),val version:Int=1,
    val inputName:String="",val outputName:String="",val processingMode:String="multiarray",
    val inputWidth:Int=blockSize,val inputHeight:Int=blockSize,
    val outputWidth:Int=(blockSize-2*shrinkSize)*scale,val outputHeight:Int=(blockSize-2*shrinkSize)*scale,val catalogFile:String="")

/** Executes the original Core ML source weights in a reconstructed ONNX graph. */
class UpscaleModelManager private constructor(private val context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val mutex = Mutex()
    private var session: OrtSession? = null; private var sessionFile: String? = null
    private val settings get() = SettingsStore(AppRepository.get(context))
    val enabledFile: String? get() = settings.string("Data.enabledModelFile").takeIf(String::isNotBlank)?.let {
        it.substringBeforeLast('.').plus(".onnx")
    }
    fun models(): List<UpscaleModel> {
        val bundled = listOf("waifu2x_photo_noise0_scale2x", "RealESRGAN_x2plus").map { name ->
            val j = JSONObject(context.assets.open("upscale/$name.json").bufferedReader().use { it.readText() })
            model(j, true)
        }
        val extra = root.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file -> runCatching { model(JSONObject(file.readText()), false) }.getOrNull() }
        return (bundled + extra).distinctBy { it.file }
    }
    private fun model(j: JSONObject, bundled: Boolean):UpscaleModel {
        val name=j.getString("file");require(name.matches(Regex("[A-Za-z0-9_.-]+\\.onnx")) && File(root,name).canonicalFile.parentFile==root.canonicalFile) { "无效模型文件名" }
        val mode=j.optString("processingMode","multiarray")
        val block=j.optInt("blockSize",256);val shrink=if(mode=="image")0 else j.optInt("shrinkSize");val scale=if(mode=="image")1 else j.optInt("scale",2)
        val output=((block.toLong()-2L*shrink)*scale).also { require(it in 1..Int.MAX_VALUE.toLong()) { "无效模型输出尺寸" } }.toInt()
        return UpscaleModel(name, j.optString("name"), block,
            shrink, scale, j.optInt("tileOverlap"), j.optLong("size"), j.optString("sha256"), bundled, File(root,name).exists(),j.optBoolean("inputHalfStep",bundled),j.optBoolean("preserveGrayscale",true),
            j.optString("info"),j.optJSONArray("tags").strings(),j.optInt("version",1),
            j.optString("inputName",j.optJSONObject("config")?.optString("inputName").orEmpty()),
            j.optString("outputName",j.optJSONObject("config")?.optString("outputName").orEmpty()),
            mode,j.optInt("inputWidth",block),j.optInt("inputHeight",block),
            j.optInt("outputWidth",output),j.optInt("outputHeight",output),j.optString("catalogFile")).also { UpscaleGeometry.validate(it) }
    }
    suspend fun install(model: UpscaleModel) = withContext(Dispatchers.IO) {
        require(model.bundled) { "模型不是内置可安装模型" }
        val file = File(root, model.file); val tmp = File(root, "${model.file}.writing")
        try {
            context.assets.open("upscale/${model.file}").use { input -> tmp.outputStream().use { ArchiveImporter.copyBounded(input, it, 256L * 1024 * 1024) } }
            val hash = hashFile(tmp)
            require(hash == model.sha256) { "模型校验失败" }; check(tmp.renameTo(file))
            context.assets.open("upscale/${model.file.substringBeforeLast('.')}.json").use { input -> File(root, "${model.file}.json").outputStream().use { input.copyTo(it) } }
        } finally { tmp.delete() }
    }
    suspend fun enable(model: UpscaleModel?) {
        if (model != null && !File(root, model.file).exists()) install(model)
        settings.put("Data.enabledModelFile", model?.file)
    }
    suspend fun delete(model: UpscaleModel) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (sessionFile == model.file) { session?.close(); session = null; sessionFile = null }
            val file = File(root, model.file).canonicalFile; require(file.parentFile == root.canonicalFile)
            file.delete(); File(root, "${model.file}.json").delete()
        }
        if (enabledFile == model.file) settings.put("Data.enabledModelFile", null)
    }
    suspend fun import(uri: Uri): UpscaleModel = withContext(Dispatchers.IO) {
        val file = File(root, "custom-${java.util.UUID.randomUUID()}.onnx")
        val incoming=File(root,"${file.name}.incoming");var metadata=JSONObject()
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> incoming.outputStream().use { ArchiveImporter.copyBounded(input, it, 256L * 1024 * 1024) } } ?: error("无法打开模型")
            val zip=incoming.inputStream().use{it.read()==0x50 && it.read()==0x4b}
            if(zip)java.util.zip.ZipFile(incoming).use { archive ->
                require(archive.size()<=8){"模型包包含过多文件"}
                val models=archive.entries().asSequence().filter{!it.isDirectory && it.name.endsWith(".onnx",true)}.toList()
                require(models.size==1){"模型包需要恰好一个 ONNX 图"}
                archive.getEntry("model.json")?.let { entry ->metadata=JSONObject(archive.getInputStream(entry).use { input ->java.io.ByteArrayOutputStream().also{ArchiveImporter.copyBounded(input,it,1024*1024)}.toString(Charsets.UTF_8.name()) }) }
                archive.getInputStream(models.single()).use{input->file.outputStream().use{ArchiveImporter.copyBounded(input,it,256L*1024*1024)}}
                metadata.optString("sha256").takeIf(String::isNotBlank)?.let{expected->require(expected==hashFile(file)){"模型包校验失败"}}
            } else check(incoming.renameTo(file))
            OrtSession.SessionOptions().use { options ->OrtEnvironment.getEnvironment().createSession(file.path, options).use { test ->
                require(test.inputInfo.size==1){"模型需要一个图像输入"}
                val inputName=metadata.optString("inputName",metadata.optJSONObject("config")?.optString("inputName").orEmpty()).ifBlank { test.inputNames.single() }
                val input = test.inputInfo[inputName]?.info as? TensorInfo ?: error("模型没有配置的图像输入：$inputName")
                val outputName=metadata.optString("outputName",metadata.optJSONObject("config")?.optString("outputName").orEmpty()).ifBlank {
                    if("output" in test.outputNames)"output" else test.outputInfo.entries.firstOrNull { (_,node)->
                        (node.info as? TensorInfo)?.shape?.let { shape->shape.size==4 && shape[0]==1L && shape[1]==3L && shape[2]>0 && shape[3]>0 }==true
                    }?.key ?: test.outputNames.first()
                }
                val output = test.outputInfo[outputName]?.info as? TensorInfo ?: error("模型没有配置的图像输出：$outputName")
                require(input.type==OnnxJavaType.FLOAT && output.type==OnnxJavaType.FLOAT) { "需要 float32 输入和输出" }
                fun dimensions(info:TensorInfo):Pair<Int,Int> {
                    require(info.shape.size==4 && info.shape[0]==1L && info.shape[1]==3L && info.shape[2] in 1..Int.MAX_VALUE.toLong() && info.shape[3] in 1..Int.MAX_VALUE.toLong()) { "需要固定 NCHW RGB 图像张量（1×3×H×W）" }
                    val width=info.shape[3].toInt();val height=info.shape[2].toInt();UpscaleGeometry.checkPixels(width,height);return width to height
                }
                val (inputWidth,inputHeight)=dimensions(input);val (outputWidth,outputHeight)=dimensions(output)
                val block = inputWidth
                val properties=test.metadata.customMetadata
                fun dimension(name:String,actual:Int) {
                    val value=if(metadata.has(name))metadata.getInt(name) else properties["aidoku.$name"]?.toIntOrNull()
                    require(value==null || value==actual) { "模型 $name 元数据与实际张量尺寸不匹配" }
                }
                dimension("inputWidth",inputWidth);dimension("inputHeight",inputHeight);dimension("outputWidth",outputWidth);dimension("outputHeight",outputHeight)
                val mode=metadata.optString("processingMode").ifBlank { properties["aidoku.processingMode"].orEmpty() }.ifBlank {
                    if(metadata.optString("type")=="image" || inputWidth!=inputHeight || outputWidth!=outputHeight)"image" else "multiarray"
                }
                val scale=if(mode=="image")1 else if(metadata.has("scale"))metadata.getInt("scale") else properties["aidoku.scale"]?.toIntOrNull() ?: (output.shape[2]/block).toInt()
                val shrink=if(mode=="image")0 else if(metadata.has("shrinkSize"))metadata.getInt("shrinkSize") else properties["aidoku.shrinkSize"]?.toIntOrNull() ?: 0
                val overlap=if(mode=="image")0 else if(metadata.has("tileOverlap"))metadata.getInt("tileOverlap") else properties["aidoku.tileOverlap"]?.toIntOrNull() ?: 0
                UpscaleGeometry.validate(mode,inputWidth,inputHeight,outputWidth,outputHeight,block,shrink,scale,overlap)
                val halfStep=if(metadata.has("inputHalfStep"))metadata.getBoolean("inputHalfStep") else properties["aidoku.inputHalfStep"]?.toBooleanStrictOrNull() ?: false
                val gray=if(metadata.has("preserveGrayscale"))metadata.getBoolean("preserveGrayscale") else properties["aidoku.preserveGrayscale"]?.toBooleanStrictOrNull() ?: true
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(FloatArray(3*inputWidth*inputHeight){.5f}),input.shape).use { tensor ->
                    test.run(mapOf(inputName to tensor),setOf(outputName)).use { result ->
                        val actual=result[0] as? OnnxTensor ?: error("模型没有图像张量输出")
                        require((actual.info as TensorInfo).shape.contentEquals(output.shape)){"模型实际输出尺寸不匹配"}
                        val values=actual.floatBuffer
                        while(values.hasRemaining())require(values.get().isFinite()){"模型输出包含非有限数值"}
                    }
                }
                val j = JSONObject(metadata.toString()).put("file", file.name).put("name", metadata.optString("name").ifBlank{uri.lastPathSegment?.substringAfterLast('/') ?: file.name})
                    .put("inputName",inputName).put("outputName",outputName)
                    .put("processingMode",mode).put("inputWidth",inputWidth).put("inputHeight",inputHeight).put("outputWidth",outputWidth).put("outputHeight",outputHeight)
                    .put("blockSize", block).put("scale", scale).put("shrinkSize",shrink).put("tileOverlap",overlap).put("inputHalfStep",halfStep).put("preserveGrayscale",gray)
                    .put("sha256", hashFile(file)).put("size", file.length())
                File(root, "${file.name}.json").writeText(j.toString()); model(j, false)
            } }
        } catch (e: Exception) { file.delete();File(root,"${file.name}.json").delete(); throw e }finally{incoming.delete()}
    }
    suspend fun upscale(input: Bitmap, maxHeight: Int, best: Boolean): Bitmap = withContext(Dispatchers.Default) {
        val enabled = enabledFile ?: return@withContext input
        if (input.height > maxHeight) return@withContext input
        val model = models().firstOrNull { it.file == enabled } ?: return@withContext input
        if (!model.installed) install(model)
        UpscaleGeometry.validate(model)
        if(model.processingMode!="image")UpscaleGeometry.scaledDimensions(input.width,input.height,model.scale)
        mutex.withLock {
            if (sessionFile != enabled) {
                session?.close()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors())); options.setInterOpNumThreads(1)
                    session = OrtEnvironment.getEnvironment().createSession(File(root, enabled).path, options)
                }; sessionFile = enabled
            }
            val engine = checkNotNull(session); val env = OrtEnvironment.getEnvironment()
            if(model.processingMode=="image")return@withLock image(engine,model,input)
            if(best && model.shrinkSize==0 && model.overlap>0)return@withLock overlapping(engine,model,input)
            val block = model.blockSize; val halo = model.shrinkSize
            val core = block - 2 * halo; require(core > 0)
            val output = Bitmap.createBitmap(input.width * model.scale, input.height * model.scale, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(input.width * input.height); input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            val stride = block * block; val floats = FloatArray(stride * 3)
            val grayscale = BooleanArray(pixels.size) { i -> val p=pixels[i]
                maxOf(Color.red(p),Color.green(p),Color.blue(p))-minOf(Color.red(p),Color.green(p),Color.blue(p)) <= 2 }
            fun starts(length: Int): List<Int> { if (length <= core) return listOf(0)
                val regular=(0..length-core step core).toList();return if (regular.last()==length-core) regular else regular+(length-core) }
            for (top in starts(input.height)) for (left in starts(input.width)) {
                currentCoroutineContext().ensureActive()
                for (y in 0 until block) for (x in 0 until block) {
                    val sourceX=left+x-halo;val sourceY=top+y-halo
                    val px = sourceX.coerceIn(0, input.width - 1); val py = sourceY.coerceIn(0, input.height - 1)
                    val color = pixels[py * input.width + px]; val index = y * block + x
                    val alpha=Color.alpha(color)/255f
                    // MultiArrayModel.expand uses RGB/255 + half an 8-bit step for real pixels; replicated halo omits the bias.
                    val bias=if(model.inputHalfStep && sourceX in 0 until input.width && sourceY in 0 until input.height) .00196078411f else 0f
                    floats[index] = (Color.red(color)*alpha).toInt() / 255f+bias
                    floats[index + stride] = (Color.green(color)*alpha).toInt() / 255f+bias
                    floats[index + stride * 2] = (Color.blue(color)*alpha).toInt() / 255f+bias
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, 3, block.toLong(), block.toLong())).use { tensor ->
                    engine.run(mapOf(model.inputName.ifBlank { engine.inputNames.first() } to tensor),setOf(model.outputName.ifBlank { engine.outputNames.first() })).use { prediction ->
                        val result = prediction[0] as OnnxTensor; val shape = (result.info as TensorInfo).shape
                        val height = shape[2].toInt(); val width = shape[3].toInt(); val out = result.floatBuffer
                        val values = FloatArray(out.remaining()); out.get(values); val channel = width * height
                        val crop = if (model.shrinkSize > 0) 0 else halo * model.scale
                        val rows = min(core, input.height - top) * model.scale; val cols = min(core, input.width - left) * model.scale
                        val tile = IntArray(rows * cols)
                        for (y in 0 until rows) for (x in 0 until cols) {
                            val i = (y + crop) * width + x + crop
                            var r = (values[i].coerceIn(0f, 1f) * 255).toInt(); var g = (values[i+channel].coerceIn(0f, 1f)*255).toInt(); var b = (values[i+channel*2].coerceIn(0f, 1f)*255).toInt()
                            val sourceIndex=(top+y/model.scale)*input.width+left+x/model.scale
                            if (model.preserveGrayscale && grayscale[sourceIndex]) { val l = (54*r+183*g+19*b+128) shr 8; r=l; g=l; b=l }
                            tile[y*cols+x] = Color.rgb(r,g,b)
                        }
                        output.setPixels(tile, 0, cols, left*model.scale, top*model.scale, cols, rows)
                    }
                }
            }
            output
        }
    }
    /** Vision's .scaleFill processes the entire image once; image models do not use tile/gray-mask options. */
    private suspend fun image(engine:OrtSession,model:UpscaleModel,input:Bitmap):Bitmap {
        currentCoroutineContext().ensureActive()
        val scaled=if(input.width==model.inputWidth && input.height==model.inputHeight)input else Bitmap.createScaledBitmap(input,model.inputWidth,model.inputHeight,true)
        val stride=model.inputWidth*model.inputHeight;val pixels=IntArray(stride);val floats=FloatArray(stride*3)
        try {
            scaled.getPixels(pixels,0,model.inputWidth,0,0,model.inputWidth,model.inputHeight)
            for(i in pixels.indices) {
                val color=pixels[i];val alpha=Color.alpha(color)/255f
                floats[i]=(Color.red(color)*alpha).toInt()/255f;floats[i+stride]=(Color.green(color)*alpha).toInt()/255f;floats[i+stride*2]=(Color.blue(color)*alpha).toInt()/255f
            }
        } finally { if(scaled!==input)scaled.recycle() }
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(floats),longArrayOf(1,3,model.inputHeight.toLong(),model.inputWidth.toLong())).use { tensor ->
            engine.run(mapOf(model.inputName.ifBlank { engine.inputNames.first() } to tensor),setOf(model.outputName.ifBlank { engine.outputNames.first() })).use { prediction ->
                val result=prediction[0] as? OnnxTensor ?: error("模型没有图像张量输出")
                require((result.info as TensorInfo).shape.contentEquals(longArrayOf(1,3,model.outputHeight.toLong(),model.outputWidth.toLong()))) { "模型实际输出尺寸不匹配" }
                val data=result.floatBuffer;val channel=model.outputWidth*model.outputHeight;val output=Bitmap.createBitmap(model.outputWidth,model.outputHeight,Bitmap.Config.ARGB_8888)
                try {
                    val line=IntArray(model.outputWidth)
                    for(y in 0 until model.outputHeight) {
                        currentCoroutineContext().ensureActive()
                        for(x in line.indices) {
                            val i=y*model.outputWidth+x
                            fun component(index:Int):Int { val v=data.get(index);require(v.isFinite()) { "模型输出包含非有限数值" };return (v.coerceIn(0f,1f)*255).toInt() }
                            line[x]=Color.rgb(component(i),component(i+channel),component(i+channel*2))
                        }
                        output.setPixels(line,0,model.outputWidth,0,y,model.outputWidth,1)
                    }
                    output
                } catch(error:Throwable) { output.recycle();throw error }
            }
        }
    }
    /** The original best mode blends overlap horizontally, then vertically using its cosine ramp and UInt8 rounding. */
    private suspend fun overlapping(engine:OrtSession,model:UpscaleModel,input:Bitmap):Bitmap {
        val block=model.blockSize;val scale=model.scale;val overlap=model.overlap.coerceAtMost(block/2);val outOverlap=overlap*scale
        val outWidth=input.width*scale;val output=Bitmap.createBitmap(outWidth,input.height*scale,Bitmap.Config.ARGB_8888)
        val source=IntArray(input.width*input.height);input.getPixels(source,0,input.width,0,0,input.width,input.height)
        val floats=FloatArray(3*block*block);val channel=block*block;val ramp=FloatArray(outOverlap) { index ->(0.5-0.5*kotlin.math.cos(Math.PI*(index*2+1)/(outOverlap*2))).toFloat() }
        fun starts(length:Int):List<Int> { val result=mutableListOf(0);while(result.last()+block<length)result+=result.last()+block-overlap;return result }
        fun blend(previous:Int,next:Int,weight:Float)=Color.rgb(
            (Color.red(previous)*(1-weight)+Color.red(next)*weight).roundToInt(),(Color.green(previous)*(1-weight)+Color.green(next)*weight).roundToInt(),
            (Color.blue(previous)*(1-weight)+Color.blue(next)*weight).roundToInt())
        try {
            for((rowIndex,top) in starts(input.height).withIndex()) {
                val rows=min(block,input.height-top)*scale;val row=IntArray(outWidth*rows)
                for((columnIndex,left) in starts(input.width).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    for(y in 0 until block)for(x in 0 until block) {
                        val px=source[(top+y).coerceAtMost(input.height-1)*input.width+(left+x).coerceAtMost(input.width-1)]
                        val alpha=Color.alpha(px)/255f;val bias=if(model.inputHalfStep).00196078411f else 0f;val i=y*block+x
                        floats[i]=(Color.red(px)*alpha).toInt()/255f+bias;floats[i+channel]=(Color.green(px)*alpha).toInt()/255f+bias;floats[i+channel*2]=(Color.blue(px)*alpha).toInt()/255f+bias
                    }
                    OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(floats),longArrayOf(1,3,block.toLong(),block.toLong())).use { tensor ->
                        engine.run(mapOf(model.inputName.ifBlank { engine.inputNames.first() } to tensor),setOf(model.outputName.ifBlank { engine.outputNames.first() })).use { prediction ->
                            val values=(prediction[0] as OnnxTensor).floatBuffer;val data=FloatArray(values.remaining());values.get(data)
                            val side=block*scale;val stride=side*side;val columns=min(block,input.width-left)*scale
                            for(y in 0 until rows)for(x in 0 until columns) {
                                val i=y*side+x;val next=Color.rgb((data[i].coerceIn(0f,1f)*255).toInt(),(data[i+stride].coerceIn(0f,1f)*255).toInt(),(data[i+stride*2].coerceIn(0f,1f)*255).toInt())
                                val destination=y*outWidth+left*scale+x;row[destination]=if(columnIndex>0 && x<outOverlap)blend(row[destination],next,ramp[x]) else next
                            }
                        }
                    }
                }
                if(rowIndex>0) {
                    val previous=IntArray(outWidth*min(outOverlap,rows));output.getPixels(previous,0,outWidth,0,top*scale,outWidth,min(outOverlap,rows))
                    for(y in 0 until min(outOverlap,rows))for(x in 0 until outWidth) { val i=y*outWidth+x;row[i]=blend(previous[i],row[i],ramp[y]) }
                }
                output.setPixels(row,0,outWidth,0,top*scale,outWidth,rows)
            }
            if(model.preserveGrayscale) {
                val line=IntArray(outWidth)
                for(y in 0 until output.height) { output.getPixels(line,0,outWidth,0,y,outWidth,1)
                    for(x in line.indices) { val original=source[y/scale*input.width+x/scale]
                        if(maxOf(Color.red(original),Color.green(original),Color.blue(original))-minOf(Color.red(original),Color.green(original),Color.blue(original))<=2) {
                            val px=line[x];val l=(54*Color.red(px)+183*Color.green(px)+19*Color.blue(px)+128) shr 8;line[x]=Color.rgb(l,l,l)
                        }
                    };output.setPixels(line,0,outWidth,0,y,outWidth,1)
                }
            }
            return output
        } catch(error:Throwable){output.recycle();throw error}
    }
    companion object {
        private fun hashFile(file:File):String {
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            file.inputStream().use { input ->while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        @Volatile private var singleton: UpscaleModelManager? = null
        fun get(context: Context):UpscaleModelManager {
            val app=context.applicationContext
            return singleton?.takeIf { it.context===app } ?: synchronized(this) { singleton?.takeIf { it.context===app } ?: UpscaleModelManager(app).also { singleton=it } }
        }
    }
}
