package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MainActivity
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.ui.ReaderScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderDirectionInstrumentedTest {
    @Test fun actualDoubleSpreadsFollowReadingModeUnderBothArabicAndEnglishUiDirection() = runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>();val context=ReaderFixtureContext(base);val repo=AppRepository.get(context)
        val folder=File(context.filesDir,"reader-verification/${UUID.randomUUID()}").apply{mkdirs()}
        fun page(name:String,color:Int):String { val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            val file=File(folder,name);file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle();return Uri.fromFile(file).toString() }
        val chapter=Chapter("chapter","Actual double spread",1.0,pages=listOf(page("1.png",Color.RED),page("2.png",Color.BLUE)),total=2,downloaded=true)
        val manga=Manga("direction-${UUID.randomUUID()}","local","Reader direction",chapters=listOf(chapter),inLibrary=true)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        var active:MainActivity?=null;var currentVm:MonogatariViewModel?=null;var currentUi:Context=context
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for(locale in listOf("en","ar"))for(mode in listOf("ltr","rtl")) {
                    val state=AppState(manga=listOf(manga),settings=ReaderOptions(mode=mode,pageLayout="double",fullscreen=false,background="white").toLegacy(JSONObject()).put("Dictionary.enable",false).toString())
                    repo.change{state}
                    scenario.onActivity { activity ->
                        val vm=context.model(repo).apply{key=manga.id;chapterKey=chapter.key;readerChapter=chapter;screen="reader"}
                        val language=context.ui(activity,locale)
                        active=activity;currentVm=vm;currentUi=language
                        activity.setContent { MaterialTheme { CompositionLocalProvider(LocalContext provides language,LocalActivityResultRegistryOwner provides activity,LocalLayoutDirection provides if(locale=="ar")LayoutDirection.Rtl else LayoutDirection.Ltr){ReaderScreen(vm,state)} } }
                    }
                    var proof:Bitmap?=null;val timeout=System.currentTimeMillis()+10000
                    while(System.currentTimeMillis()<timeout) {
                        Thread.sleep(120);val shot=automation.takeScreenshot() ?: continue
                        val left=shot.getPixel(shot.width/4,shot.height/2);val right=shot.getPixel(shot.width*3/4,shot.height/2)
                        val matches=if(mode=="rtl")Color.blue(left)>Color.red(left)+120 && Color.red(right)>Color.blue(right)+120 else Color.red(left)>Color.blue(left)+120 && Color.blue(right)>Color.red(right)+120
                        if(matches){proof=shot;break}else shot.recycle()
                    }
                    if(proof==null)context.diagnose("reader-direction-failure-$locale-$mode",currentVm!!,active,currentUi)
                    assertNotNull("Real reader $locale/$mode must have the requested physical blue/red order",proof)
                    val out=File(context.getExternalFilesDir(null),"verification").apply{mkdirs()}
                    File(out,"reader-direction-$locale-$mode.png").outputStream().use{proof!!.compress(Bitmap.CompressFormat.PNG,100,it)};proof!!.recycle()
                }
            }
        } finally { Thread.sleep(400);context.clear() }
    }
}
