package app.monogatari.android.reader

import app.monogatari.android.AppBrand

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.monogatari.android.ui.tr
import app.monogatari.android.ui.OptionSheet
import kotlinx.coroutines.launch

@Composable fun UpscaleModelsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { UpscaleModelManager.get(context) }
    val catalog=remember { UpscaleModelCatalog(context) }
    var available by remember { mutableStateOf(catalog.cached()) }
    var conversion by remember { mutableStateOf<CatalogUpscaleModel?>(null) }
    var pendingExport by remember { mutableStateOf<CatalogUpscaleModel?>(null) }
    var received by remember { mutableLongStateOf(0L) }
    var models by remember { mutableStateOf(manager.models()) }; var selected by remember { mutableStateOf(manager.enabledFile) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) { scope.launch { busy=true; error=""; try { block(); models=manager.models(); selected=manager.enabledFile }
        catch (e:kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error=e.message.orEmpty() } finally { busy=false } } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) action {
        val previous=models.firstOrNull { it.file==manager.enabledFile };val imported=manager.import(uri)
        val remote=available.firstOrNull { it.matches(imported) }
        if(previous!=null && imported.catalogFile.isNotBlank() && remote?.matches(previous)==true && imported.version>previous.version)manager.enable(imported)
    } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val model=pendingExport;pendingExport=null
        if(uri!=null && model!=null)action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri,"wt")?.use { catalog.export(model,it) } ?: error("Cannot save model source")
        } }
    }
    LaunchedEffect(Unit) { action { available=catalog.refresh() } }
    OptionSheet(tr("UPSCALING_MODELS"), onDismiss, scrollable = false) {
        Column(Modifier.padding(horizontal = 20.dp).weight(1f, fill = false)) {
            Text("ONNX · CPU · ${tr("UPSCALE_QUALITY")}", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error)
            TextButton({action { available=catalog.refresh() }},enabled=!busy) { Text(tr("UPDATE_NOW")) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item { Text(tr("UPSCALING_MODELS"),style=MaterialTheme.typography.titleSmall) }
                items(models, key={it.file}) { model ->
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    Text(model.name, style=MaterialTheme.typography.titleSmall)
                    if(model.info.isNotBlank())Text(model.info,style=MaterialTheme.typography.bodySmall)
                    if(model.tags.isNotEmpty())Text(model.tags.joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                    val dimensions=if(model.processingMode=="image")"${model.inputWidth}×${model.inputHeight} → ${model.outputWidth}×${model.outputHeight}" else "${model.scale}× · ${model.blockSize}×${model.blockSize}"
                    Text("$dimensions · ${if (model.file == selected) tr("ENABLED_MODEL") else model.file}", style=MaterialTheme.typography.bodySmall)
                    Text("v${model.version} · ${"%.1f".format(model.size/1048576.0)} MiB"+(if(model.processingMode=="image")"" else " · halo ${model.shrinkSize} · overlap ${model.overlap}"),style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton({ action { manager.enable(model) } }, enabled=!busy) { Text(if (model.file==selected) tr("ENABLED_MODEL") else if (model.installed) tr("ENABLE") else tr("DOWNLOAD")) }
                        if (model.installed) TextButton({ action { manager.delete(model) } }, enabled=!busy) { Text(tr("DELETE")) }
                    }
                }
                }
                item { Text(tr("AVAILABLE_MODELS"),style=MaterialTheme.typography.titleSmall) }
                items(available,key={"catalog:${it.originalFile}"}) { remote ->
                    val installed=remote.installed(models)
                    Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                        Text(remote.name,style=MaterialTheme.typography.titleSmall)
                        Text("v${remote.version} · ${remote.type}"+(if(remote.size>0)" · ${"%.1f".format(remote.size/1048576.0)} MiB" else ""),style=MaterialTheme.typography.bodySmall)
                        if(remote.info.isNotBlank())Text(remote.info,style=MaterialTheme.typography.bodySmall)
                        if(remote.tags.isNotEmpty())Text(remote.tags.joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                        if(installed!=null)Text("${tr("INSTALLED")}: v${installed.version}",style=MaterialTheme.typography.labelSmall)
                        TextButton({conversion=remote;received=0},enabled=!busy) { Text(if(installed!=null && remote.needsUpdate(models))tr("BUTTON_UPDATE") else tr("MODEL_INFO")) }
                    }
                }
                if(available.isEmpty())item { Text(tr("NO_AVAILABLE_MODELS_TEXT"),style=MaterialTheme.typography.bodySmall) }
            }
            TextButton({ import.launch(arrayOf("*/*")) }, enabled=!busy) { Text("${tr("IMPORT")} ONNX / ${AppBrand.NAME} model ZIP") }
            TextButton({ action { manager.enable(null) } }, enabled=!busy) { Text(tr("DISABLE")) }
        }
    }
    conversion?.let { remote ->
        val zh=LocalConfiguration.current.locales[0].language=="zh"
        AlertDialog(onDismissRequest={if(!busy)conversion=null},title={Text(remote.name)},text={
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("v${remote.version} · ${remote.type}")
                Text(if(zh)"下载的是原 Core ML 文件。导出完整源模型包，在 Windows 转换后导入生成的${AppBrand.NAME}模型 ZIP，才能在 Android 运行。" else "Download the original Core ML source. Export its complete model bundle, convert it on Windows, then import the generated ${AppBrand.NAME} model ZIP to run it on Android.")
                Text("powershell -ExecutionPolicy Bypass -File android\\model-conversion\\Convert-Model.ps1",style=MaterialTheme.typography.bodySmall)
                Text(if(zh)"也可以在 Windows 运行脚本 -Catalog 参数，从官方目录选择并直接下载转换。文件选择窗口支持直接选取导出的模型包。" else "You can also run the Windows script with -Catalog to choose and convert an official model directly. The file picker accepts this exported source bundle.",style=MaterialTheme.typography.bodySmall)
                if(received>0)Text("${"%.1f".format(received/1048576.0)} MiB")
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
                if(catalog.downloaded(remote)!=null)Text(if(remote.needsUpdate(models)) {
                    if(zh)"原文件下载完成；尚未安装此版本转换后的模型。" else "Source download is complete; this converted model version has not been installed."
                } else "${tr("INSTALLED")}: v${remote.installed(models)?.version}",style=MaterialTheme.typography.bodySmall)
                TextButton({action { catalog.download(remote) { count,_ ->received=count } }},enabled=!busy) { Text(tr("DOWNLOAD")) }
                TextButton({pendingExport=remote;export.launch("${remote.stem}.aidoku-coreml.zip")},enabled=!busy && catalog.downloaded(remote)!=null) { Text(tr("EXPORT")) }
                TextButton({import.launch(arrayOf("*/*"))},enabled=!busy) { Text("${tr("IMPORT")} ONNX / ${AppBrand.NAME} model ZIP") }
            }
        },confirmButton={TextButton({conversion=null},enabled=!busy){Text(tr("DONE"))}})
    }
}
