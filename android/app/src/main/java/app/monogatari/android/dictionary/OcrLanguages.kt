package app.monogatari.android.dictionary

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class OcrModel(val code: String, val trainedData: String? = null) {
    LATIN("en"), JAPANESE("ja"), CHINESE("zh"), KOREAN("ko"), DEVANAGARI("hi", "Devanagari"),
    CYRILLIC("ru", "Cyrillic"), THAI("th", "Thai"), ARABIC("ar", "Arabic"), HEBREW("he", "Hebrew"), GREEK("el", "Greek")
}

object OcrLanguages {
    private val latin = setOf("af", "sq", "ca", "hr", "cs", "da", "nl", "en", "et", "fil", "tl", "fi", "fr", "de", "hu", "is", "id", "it", "lv", "lt", "ms", "no", "nb", "nn", "pl", "pt", "ro", "sk", "sl", "es", "sv", "tr", "vi", "az", "eu", "bs", "ceb", "eo", "gl", "ht", "ga", "jv", "la", "mt", "sw", "uz", "cy", "zu", "ace", "fy", "haw", "ig", "mi", "oc", "so", "sr", "su", "tk", "yo", "latn")
    fun model(language: String): OcrModel? {
        val tag = language.replace('_', '-').lowercase(Locale.ROOT); val code = tag.substringBefore('-')
        val script = Locale.forLanguageTag(tag).script.lowercase(Locale.ROOT)
        return when {
            tag.isBlank() || code == "auto" -> null
            script == "latn" -> OcrModel.LATIN
            script == "cyrl" || code in setOf("ru", "rus", "uk", "ukr", "bg", "be", "kk", "mk", "mn", "tg", "ky", "os", "cu", "sr") -> OcrModel.CYRILLIC
            script == "arab" || code in setOf("ar", "ara", "fa", "ur", "ps", "ug", "sd") -> OcrModel.ARABIC
            script == "hebr" || code in setOf("he", "heb", "iw", "yi") -> OcrModel.HEBREW
            script == "grek" || code in setOf("el", "ell", "grc") -> OcrModel.GREEK
            script == "thai" || code in setOf("th", "tha") -> OcrModel.THAI
            script == "deva" || code in setOf("hi", "hin", "mr", "ne", "sa") -> OcrModel.DEVANAGARI
            code in setOf("ja", "jpn") -> OcrModel.JAPANESE
            code in setOf("zh", "zho", "cmn", "yue") -> OcrModel.CHINESE
            code in setOf("ko", "kor") -> OcrModel.KOREAN
            code in latin || code == "eng" -> OcrModel.LATIN
            else -> throw IllegalArgumentException("OCR language '$language' has no bundled model. Supported scripts: Latin, Chinese, Japanese, Korean, Devanagari, Cyrillic, Thai, Arabic, Hebrew and Greek.")
        }
    }
    fun request(restrict: Boolean, sourceLanguage: String?, manual: String? = null): String = manual?.takeIf { it != "auto" }
        ?: if (restrict) sourceLanguage?.takeIf(String::isNotBlank) ?: "en" else "auto"
    private fun scriptScore(model: OcrModel, text: String): Float {
        val characters = text.codePoints().toArray().filter(Character::isLetterOrDigit)
        if (characters.none(Character::isLetter)) return if (model == OcrModel.LATIN) 1f else .6f
        val accepted = when (model) {
            OcrModel.LATIN -> setOf(Character.UnicodeScript.LATIN)
            OcrModel.JAPANESE -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
            OcrModel.CHINESE -> setOf(Character.UnicodeScript.HAN)
            OcrModel.KOREAN -> setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN)
            OcrModel.DEVANAGARI -> setOf(Character.UnicodeScript.DEVANAGARI)
            OcrModel.CYRILLIC -> setOf(Character.UnicodeScript.CYRILLIC)
            OcrModel.THAI -> setOf(Character.UnicodeScript.THAI)
            OcrModel.ARABIC -> setOf(Character.UnicodeScript.ARABIC)
            OcrModel.HEBREW -> setOf(Character.UnicodeScript.HEBREW)
            OcrModel.GREEK -> setOf(Character.UnicodeScript.GREEK)
        }
        // A single script letter among several misread digits is weak script evidence.
        // Counting only letters incorrectly gave Greek "Η 2-58" the same score as full Japanese "日本語".
        return characters.count { Character.UnicodeScript.of(it) in accepted || model == OcrModel.LATIN && Character.isDigit(it) }.toFloat() / characters.size
    }
    fun merge(pages: List<Pair<OcrModel, OcrPage>>, preferredLanguage: String? = null): OcrPage {
        require(pages.isNotEmpty())
        val preferred = runCatching { preferredLanguage?.let(::model) }.getOrNull()
        data class Candidate(val model: OcrModel, val observation: OcrObservation, val score: Float)
        val candidates = pages.flatMap { (model, page) -> page.observations.map { observation ->
            val confidence = observation.confidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            val score = confidence * .65f + scriptScore(model, observation.region.text) * .30f + if (preferred == model) .05f else 0f
            Candidate(model, observation, score)
        } }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.model.ordinal })
        val selected = mutableListOf<OcrObservation>()
        for (candidate in candidates) {
            val a = candidate.observation.region; val area = (a.right - a.left) * (a.bottom - a.top)
            if (area <= 0 || a.text.isBlank()) continue
            val duplicate = selected.any { previous ->
                val b = previous.region; val otherArea = (b.right - b.left) * (b.bottom - b.top)
                val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
                intersection / max(.000001f, area + otherArea - intersection) > .40f || intersection / max(.000001f, min(area, otherArea)) > .80f
            }
            if (!duplicate) selected += candidate.observation
        }
        val ordered = OcrLayout.ordered(selected, selected.indices.toList()).map(selected::get)
        val first = pages.first().second
        return OcrPage(ordered.joinToString("\n") { it.region.text }, ordered.map { it.region }, first.width, first.height, ordered)
    }
}
