package app.monogatari.android.reader

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.monogatari.android.ui.tr
import app.monogatari.android.ui.pt
import app.monogatari.android.ui.SettingRow
import app.monogatari.android.ui.LocalSheetClose
import app.monogatari.android.ui.OptionSheet
import app.monogatari.android.ui.SearchField
import app.monogatari.android.ui.SheetOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderFont(val key:String,val name:String)
object ReaderFonts {
    fun file(value:String):File? = if(!value.startsWith("file:"))null else runCatching {
        File(value.removePrefix("file:")).canonicalFile.takeIf { it.isFile && it.canRead() && it.extension in setOf("ttf","otf","ttc") &&
            listOf("/system/fonts/","/product/fonts/","/vendor/fonts/","/data/fonts/").any { root ->it.path.startsWith(root) } }
    }.getOrNull()
    fun available():List<ReaderFont> {
        val files=if(Build.VERSION.SDK_INT>=29)android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull{it.file}.distinct()
            else File("/system/fonts").listFiles().orEmpty().filter{it.extension in setOf("ttf","otf","ttc")}
        return listOf(ReaderFont("system","System"),ReaderFont("serif","Serif"),ReaderFont("mono","Monospace"),ReaderFont("cursive","Cursive"))+
            files.filter{it.isFile && it.canRead()}.sortedBy{it.name}.map{ReaderFont("file:${it.absolutePath}",it.nameWithoutExtension)}
    }
    fun typeface(value:String):Typeface = runCatching { when {
        value.startsWith("file:")->file(value)?.let(Typeface::createFromFile) ?: Typeface.DEFAULT
        value=="serif"->Typeface.SERIF;value=="mono"->Typeface.MONOSPACE;value in setOf("system","System","")->Typeface.DEFAULT
        else->Typeface.create(value,Typeface.NORMAL)
    } }.getOrDefault(Typeface.DEFAULT)
    fun family(value:String):FontFamily = if(value.startsWith("file:"))runCatching{file(value)?.let { FontFamily(Font(it)) } ?: FontFamily.Default}.getOrDefault(FontFamily.Default)
        else when(value){"serif"->FontFamily.Serif;"mono"->FontFamily.Monospace;"cursive"->FontFamily.Cursive;else->FontFamily.Default}
}

@Composable private fun readerFontName(key: String, fallback: String): String = when (key) {
    "", "System", "system" -> tr("READER_BG_COLOR_SYSTEM")
    "serif" -> pt("Serif", "衬线字体")
    "mono" -> pt("Monospace", "等宽字体")
    "cursive" -> pt("Cursive", "手写字体")
    else -> fallback
}
@Composable fun ReaderFontPicker(selected:String,enabled:Boolean=true,onChange:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    SettingRow(tr("TEXT_FONT_FAMILY"), value=readerFontName(selected,selected.removePrefix("file:").substringAfterLast('/').substringBeforeLast('.')),enabled=enabled,onClick={open=true})
    if(open && enabled) {
        var query by remember{mutableStateOf("")}
        val fonts by produceState<List<ReaderFont>>(emptyList()){value=withContext(Dispatchers.IO){ReaderFonts.available()}}
        OptionSheet(tr("TEXT_FONT_FAMILY"),{open=false},scrollable=false) {
            val close=LocalSheetClose.current
            SearchField(query,tr("SEARCH")){query=it}
            val shown=fonts.filter{it.name.contains(query,true)}
            LazyColumn(Modifier.fillMaxWidth().padding(top=6.dp)){itemsIndexed(shown,key={_,font->font.key}){index,font->
                SheetOption(readerFontName(font.key,font.name),selected==font.key,{onChange(font.key);close()},fontFamily=ReaderFonts.family(font.key),divider=index<shown.lastIndex)
            }}
        }
    }
}
