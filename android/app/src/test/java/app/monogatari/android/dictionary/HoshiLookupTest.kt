package app.monogatari.android.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HoshiLookupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun allPinnedRulesHandleIrregularAndDeepCausativePassiveConjugations() {
        val engine = HoshiDeinflector(context); assertEquals(889, engine.ruleCount)
        for ((surface, lemma) in listOf("食べさせられませんでした" to "食べる", "書かせられなかった" to "書く", "行っちゃった" to "行く", "読んでいました" to "読む", "來ませんでした" to "來る", "良くなければ" to "良い")) assertTrue("$surface → $lemma", engine.candidates(surface).any { it.text == lemma })
    }
    @Test fun allOriginalPreprocessorsKeepSupplementaryKanjiAndNormalizeVoiceWidthAndIteration() {
        val engine = HoshiDeinflector(context)
        assertTrue("カタカナ" in engine.preprocess("ｶﾀｶﾅ")); assertTrue("かが" in engine.preprocess("かゞ")); assertTrue("丈" in engine.preprocess("𠀋")); assertTrue("Ａ一" in engine.preprocess("A1")); assertTrue("すごい" in engine.preprocess("すっっごーーい"))
    }
    @Test fun actualDatabaseLookupFiltersWrongPartOfSpeechAndReturnsLongestMatchedInflection() = runBlocking {
        val archive = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> mapOf("index.json" to """{"title":"Full Hoshi Fixture","revision":"1"}""", "term_bank_1.json" to """[["食べる","たべる","","v1",0,["to eat"],1,""],["食べる","","","n",0,["incorrect noun conjugation"],2,""],["食","","","",0,["food"],3,""]]""").forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } } }.toByteArray()
        val dictionary = DictionaryRepository.get(context); dictionary.importDictionary(archive.inputStream())
        val found = dictionary.lookupAt("食べさせられませんでしたよ", 0); assertEquals("食べさせられませんでした", found.first); assertEquals("食べる", found.second.first().expression); assertFalse(found.second.any { it.definition.contains("incorrect noun") }); assertTrue(found.second.any { it.expression == "食" })
    }
}
