package app.monogatari.android.dictionary

import org.json.JSONObject
import java.util.UUID

data class DictionaryInfo(val id: String, val title: String, val revision: String, val terms: Int, val enabled: Boolean = true, val priority: Int = 0,
    val category: String = "none", val author: String = "", val description: String = "", val indexUrl: String = "", val downloadUrl: String = "", val kinds: String = "term")
data class DictionaryEntry(val expression: String, val reading: String, val definition: String, val dictionary: String, val tags: String = "", val frequency: String = "", val pitch: String = "", val reason: String = "", val dictionaryId: String = "", val glossary: String = "")
data class VocabularyEntry(val id: String = UUID.randomUUID().toString(), val word: String, val reading: String = "", val definition: String = "", val sentence: String = "",
    val mangaId: String = "", val chapterKey: String = "", val page: Int = 0, val cloze: String = "", val createdAt: Long = System.currentTimeMillis(), val imagePath: String = "", val noteId: Long = 0,
    val clozeOffset: Int? = null, val iosFields: String = "{}", val unknownFields: String = "{}") {
    fun json() = JSONObject(unknownFields).put("id", id).put("word", word).put("reading", reading).put("definition", definition).put("sentence", sentence).put("mangaId", mangaId).put("chapterKey", chapterKey)
        .put("page", page).put("cloze", cloze).put("createdAt", createdAt).put("imagePath", imagePath).put("noteId", noteId).put("clozeOffset", clozeOffset ?: JSONObject.NULL).put("iosFields", JSONObject(iosFields))
    companion object {
        private val known = setOf("id", "word", "reading", "definition", "sentence", "mangaId", "chapterKey", "page", "cloze", "createdAt", "imagePath", "noteId", "clozeOffset", "iosFields")
        private fun number(j: JSONObject, key: String, default: Long, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
            if (j.isNull(key)) return default
            val raw = j.get(key); require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble() && raw.toDouble() >= min.toDouble() && raw.toDouble() <= max.toDouble()) { "Invalid vocabulary $key" }
            if (max == Long.MAX_VALUE && (raw is Double || raw is Float)) require(raw.toDouble() < Long.MAX_VALUE.toDouble()) { "Invalid vocabulary $key" }
            return raw.toLong()
        }
        fun parse(j: JSONObject): VocabularyEntry {
            fun string(key: String) = if (j.isNull(key)) "" else j.optString(key)
            val offset = if (j.isNull("clozeOffset")) null else number(j, "clozeOffset", 0, -32768, 32767).toInt()
            require(j.isNull("iosFields") || j.opt("iosFields") is JSONObject) { "Invalid vocabulary original fields" }
            val unknown = JSONObject(j.toString()); known.forEach(unknown::remove)
            return VocabularyEntry(j.optString("id", UUID.randomUUID().toString()), j.getString("word"), string("reading"), string("definition"), string("sentence"),
                string("mangaId"), string("chapterKey"), number(j, "page", 0, 0, Int.MAX_VALUE.toLong()).toInt(), string("cloze"), number(j, "createdAt", System.currentTimeMillis()), string("imagePath"), number(j, "noteId", 0),
                offset, j.optJSONObject("iosFields")?.toString() ?: "{}", unknown.toString())
        }
    }
}
object JapaneseLookup {
    private val kana: Map<String, String> = buildMap {
        listOf("a", "i", "u", "e", "o").zip("あいうえお".map { it.toString() }).forEach { (k, v) -> put(k, v) }
        listOf("k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの", "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご", "z" to "ざじずぜぞ", "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ").forEach { (consonant, chars) -> listOf("a", "i", "u", "e", "o").forEachIndexed { i, vowel -> put(consonant + vowel, chars[i].toString()) } }
        putAll(mapOf("shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ", "ya" to "や", "yu" to "ゆ", "yo" to "よ", "wa" to "わ", "wo" to "を"))
        listOf("ky" to "き", "sy" to "し", "sh" to "し", "ty" to "ち", "ch" to "ち", "ny" to "に", "hy" to "ひ", "my" to "み", "ry" to "り", "gy" to "ぎ", "j" to "じ", "by" to "び", "py" to "ぴ").forEach { (prefix, base) -> listOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ").forEach { (v, small) -> put(prefix + v, base + small) } }
    }
    fun romaji(value: String): Set<String> {
        val input = value.lowercase().filter { !it.isWhitespace() && it != '-' }
        if (input.isEmpty() || input.any { it !in 'a'..'z' && it != '\'' }) return emptySet()
        val results = linkedSetOf<String>(); val queue = java.util.ArrayDeque<Pair<Int, String>>().apply { add(0 to "") }; var visited = 0
        while (queue.isNotEmpty() && visited++ < 1024 && results.size < 32) {
            val (index, current) = queue.removeLast(); if (index == input.length) { results += current; continue }
            val c = input[index]; val next = input.getOrNull(index + 1)
            if (next == c && c !in "aeioun") queue.add(index + 1 to current + "っ")
            if (c == 'n') { if (next == null) queue.add(index + 1 to current + "ん") else if (next == '\'') queue.add(index + 2 to current + "ん") else if (next !in "aeiou" || next == 'y') queue.add(index + 1 to current + "ん") }
            for (size in 1..3) if (index + size <= input.length) kana[input.substring(index, index + size)]?.let { queue.add(index + size to current + it) }
        }
        return results
    }
    private val replacements = listOf(
        "ました" to "る", "ませんでした" to "る", "ません" to "る", "ます" to "る", "ません" to "う", "ました" to "う",
        "ている" to "る", "ていた" to "る", "ています" to "る", "ていない" to "る", "られた" to "る", "られる" to "る", "させる" to "る",
        "ない" to "る", "なかった" to "る", "た" to "る", "て" to "る", "たい" to "る", "れば" to "る", "よう" to "る",
        "った" to "う", "った" to "つ", "った" to "る", "って" to "う", "って" to "つ", "って" to "る",
        "いた" to "く", "いて" to "く", "いだ" to "ぐ", "いで" to "ぐ", "んだ" to "む", "んだ" to "ぶ", "んだ" to "ぬ",
        "んで" to "む", "んで" to "ぶ", "んで" to "ぬ", "した" to "す", "して" to "す", "した" to "する", "して" to "する",
        "かった" to "い", "くない" to "い", "くなかった" to "い", "ければ" to "い", "くて" to "い", "かったです" to "い",
        "きます" to "く", "ぎます" to "ぐ", "します" to "す", "ちます" to "つ", "にます" to "ぬ", "びます" to "ぶ", "みます" to "む", "ります" to "る", "います" to "う",
        "きました" to "く", "ぎました" to "ぐ", "しました" to "す", "ちました" to "つ", "にました" to "ぬ", "びました" to "ぶ", "みました" to "む", "りました" to "る", "いました" to "う",
        "かない" to "く", "がない" to "ぐ", "さない" to "す", "たない" to "つ", "なない" to "ぬ", "ばない" to "ぶ", "まない" to "む", "らない" to "る", "わない" to "う",
        "できる" to "する", "こない" to "くる", "きた" to "くる", "来た" to "来る", "来ない" to "来る"
    )
    fun hiragana(value: String) = value.map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")
    fun candidates(value: String): LinkedHashMap<String, String> {
        val result = linkedMapOf(value to "", hiragana(value) to "")
        romaji(value).forEach { result[it] = "romaji" }
        var frontier = result.keys.toList()
        repeat(3) {
            val next = mutableListOf<String>()
            for (word in frontier) for ((from, to) in replacements) if (word.length > from.length && word.endsWith(from)) {
                val root = word.dropLast(from.length) + to
                if (result.size < 256 && root !in result) { result[root] = "$from → $to"; next += root }
            }
            frontier = next
        }
        return result
    }
}
