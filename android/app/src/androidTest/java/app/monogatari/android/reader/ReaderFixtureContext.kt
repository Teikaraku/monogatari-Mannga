package app.monogatari.android.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.AppRepository
import app.monogatari.android.dictionary.DictionaryRepository
import app.monogatari.android.downloads.DownloadService
import app.monogatari.android.sources.SourceRuntimeManager
import app.monogatari.android.tracking.TrackingRepository
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real Activity/Compose rendering with only fixture files, databases and preference namespaces. */
internal class ReaderFixtureContext(val original:Context):ContextWrapper(original) {
    private val root=File(original.cacheDir,"reader-device-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val names=mutableSetOf<String>()
    private val viewModels=ViewModelStore()
    fun model(repo:AppRepository):MonogatariViewModel=ViewModelProvider(viewModels,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T { require(modelClass==MonogatariViewModel::class.java);return MonogatariViewModel(repo, scheduleBackground=false) as T }
    }).get("reader-${UUID.randomUUID()}",MonogatariViewModel::class.java)
    override fun getApplicationContext():Context=this
    // API26 Context's default implementation calls applicationContext.registerComponentCallbacks again.
    override fun registerComponentCallbacks(callback:android.content.ComponentCallbacks)=original.applicationContext.registerComponentCallbacks(callback)
    override fun unregisterComponentCallbacks(callback:android.content.ComponentCallbacks)=original.applicationContext.unregisterComponentCallbacks(callback)
    override fun getFilesDir():File=File(root,"files").apply { mkdirs() }
    override fun getCacheDir():File=File(root,"cache").apply { mkdirs() }
    override fun getDatabasePath(name:String):File=File(root,"databases/$name").apply { parentFile?.mkdirs() }
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
        val scoped="${root.name}-$name";synchronized(names) { names+=scoped };return original.getSharedPreferences(scoped,mode)
    }
    fun ui(activity:Activity,language:String?=null):Context {
        val localized=language?.let { activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            val locale=Locale.forLanguageTag(it);setLocale(locale);setLayoutDirection(locale)
        }) }
        return object:ContextWrapper(activity) {
            override fun getApplicationContext():Context=this@ReaderFixtureContext
            override fun registerComponentCallbacks(callback:android.content.ComponentCallbacks)=this@ReaderFixtureContext.registerComponentCallbacks(callback)
            override fun unregisterComponentCallbacks(callback:android.content.ComponentCallbacks)=this@ReaderFixtureContext.unregisterComponentCallbacks(callback)
            override fun getResources():Resources=localized?.resources ?: super.getResources()
            override fun getFilesDir():File=this@ReaderFixtureContext.filesDir
            override fun getCacheDir():File=this@ReaderFixtureContext.cacheDir
            override fun getDatabasePath(name:String):File=this@ReaderFixtureContext.getDatabasePath(name)
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=this@ReaderFixtureContext.getSharedPreferences(name,mode)
        }
    }
    suspend fun diagnose(label:String,vm:MonogatariViewModel,activity:Activity?,uiContext:Context=this) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val output=File(original.getExternalFilesDir(null),"verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->File(output,"$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() }
        val data=JSONObject();val pages=vm.readerChapter?.pages.orEmpty()
        instrumentation.runOnMainSync {
            val focus=activity?.currentFocus;val ime=uiContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            data.put("message",vm.message).put("busy",vm.busy).put("screen",vm.screen).put("key",vm.key).put("chapter",vm.chapterKey)
                .put("page",vm.readerVisiblePage).put("total",vm.readerVisibleTotal).put("inputRegistered",ReaderInput.onPage!=null)
                .put("inputCanHandle",ReaderInput.canHandle?.invoke()).put("inputMode",ReaderInput.mode).put("volumeEnabled",ReaderInput.enabled)
                .put("focus",focus?.javaClass?.name).put("focusEditor",focus?.onCheckIsTextEditor()).put("imeAcceptingText",ime?.isAcceptingText)
                .put("windowFocus",activity?.hasWindowFocus()).put("decorWidth",activity?.window?.decorView?.width).put("decorHeight",activity?.window?.decorView?.height)
                .put("windowFlags",activity?.window?.attributes?.flags).put("secure",activity?.window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)
        }
        val nodes=JSONArray()
        fun visit(node:android.view.accessibility.AccessibilityNodeInfo?,depth:Int=0) {
            if(node==null || depth>30 || nodes.length()>400)return
            nodes.put(JSONObject().put("class",node.className?.toString()).put("text",node.text?.toString()).put("description",node.contentDescription?.toString()))
            for(i in 0 until node.childCount)visit(node.getChild(i),depth+1)
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow);data.put("nodes",nodes)
        data.put("readerErrors",JSONArray(app.monogatari.android.sources.SourceLogStore.get(uiContext).entries.value
            .filter { it.message.startsWith("Reader image load failed:") }.takeLast(12).map { it.formatted() }))
        data.put("images",withContext(Dispatchers.IO) { JSONArray().apply { pages.take(6).forEach { url ->
            val row=JSONObject().put("uri",url);val file=Uri.parse(url).path?.let(::File)
            row.put("exists",file?.isFile).put("bytes",file?.length())
            file?.let { BitmapFactory.decodeFile(it.path) }?.let { image ->row.put("decodedWidth",image.width).put("decodedHeight",image.height).put("decodedCenter",image.getPixel(image.width/2,image.height/2));image.recycle() }
            try { val result=uiContext.imageLoader.execute(ImageRequest.Builder(uiContext).data(url).size(Size.ORIGINAL).allowHardware(false).build())
                row.put("coilResult",result.javaClass.simpleName);if(result is SuccessResult)row.put("coilWidth",result.drawable.intrinsicWidth).put("coilHeight",result.drawable.intrinsicHeight)
                else if(result is coil.request.ErrorResult)row.put("coilError",result.throwable.toString())
            }catch(error:Exception) { row.put("coilError",error.toString()) }
            put(row)
        } } })
        File(output,"$label.json").writeText(data.toString(2))
    }
    fun clear() {
        ReaderInput.clear()
        viewModels.clear()
        // Restore context-scoped singletons; no real AppState ever needs replacement or rollback.
        val real=AppRepository.get(original);DictionaryRepository.get(original);TrackingRepository.get(original)
        SourceRuntimeManager.get(original);DownloadService.get(original);UpscaleModelManager.get(original)
        app.monogatari.android.library.LibraryRefresh.schedule(real);app.monogatari.android.backup.CompleteBackupManager.schedule(real)
        synchronized(names) { names.forEach(original::deleteSharedPreferences) }
        check(root.canonicalFile.parentFile==original.cacheDir.canonicalFile && root.name.startsWith("reader-device-"))
        root.deleteRecursively()
    }
}
