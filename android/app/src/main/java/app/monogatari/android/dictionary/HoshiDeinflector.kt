package app.monogatari.android.dictionary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Kotlin port of the original pinned HoshiDicts deinflector and all seven implemented preprocessors. */
class HoshiDeinflector(context: Context) {
    data class Candidate(val text: String, val conditions: Int, val trace: List<String>, val steps: Int) { val reason get() = trace.joinToString(" → ") }
    private data class Rule(val from: String, val to: String, val input: Int, val output: Int, val group: String)
    private val rules: Map<String, List<Rule>>
    private val kanji: Map<Int, Int>
    val ruleCount: Int
    init {
        val json = context.assets.open("dictionary/hoshi-transforms.json").bufferedReader().use { JSONObject(it.readText()) }
        val groups = json.getJSONArray("groups"); val source = json.getJSONArray("rules"); ruleCount = source.length()
        rules = (0 until source.length()).map { source.getJSONArray(it).let { r -> Rule(r.getString(0), r.getString(1), r.getInt(2), r.getInt(3), groups.getString(r.getInt(4))) } }.groupBy { it.from }
        val variants = context.assets.open("dictionary/hoshi-kanji-variants.json").bufferedReader().use { JSONArray(it.readText()) }
        kanji = (0 until variants.length()).associate { variants.getJSONArray(it).let { r -> r.getInt(0) to r.getInt(1) } }
    }
    fun candidates(value: String): List<Candidate> {
        val result = linkedMapOf<Pair<String, Int>, Candidate>()
        for ((variant, steps) in preprocess(value)) {
            val queue = java.util.ArrayDeque<Candidate>(); queue.add(Candidate(variant, 0, emptyList(), steps))
            while (queue.isNotEmpty()) {
                val candidate = queue.removeFirst(); val key = candidate.text to candidate.conditions
                val before = result[key]
                if (before != null && (before.steps < candidate.steps || before.steps == candidate.steps && before.trace.size <= candidate.trace.size)) continue
                result[key] = candidate
                if (candidate.text.codePointCount(0, candidate.text.length) <= 1) continue
                for (suffix in rules.keys) if (candidate.text.endsWith(suffix)) for (rule in rules.getValue(suffix)) {
                    if (candidate.conditions != 0 && candidate.conditions and rule.input == 0) continue
                    val transformed = candidate.text.dropLast(suffix.length) + rule.to
                    // Original recursion does not retain one-codepoint transformations.
                    if (transformed.codePointCount(0, transformed.length) > 1) queue.add(Candidate(transformed, rule.output, candidate.trace + rule.group, steps))
                }
            }
        }
        return result.values.sortedWith(compareBy<Candidate> { it.steps }.thenBy { it.trace.size })
    }
    fun accepts(candidate: Candidate, partOfSpeech: String): Boolean {
        if (candidate.conditions == 0) return true
        val conditions = partOfSpeech.split(Regex("\\s+")).fold(0) { bits, tag -> bits or when (tag) { "v1" -> 3; "v5" -> 28; "vk" -> 32; "vs" -> 64; "vz" -> 128; "adj-i" -> 256; else -> 0 } }
        return conditions and candidate.conditions != 0
    }
    fun preprocess(value: String): Map<String, Int> {
        var variants = linkedMapOf(value to 0)
        val processors: List<(String) -> List<String>> = listOf(
            { listOf(it, Normalizer.normalize(it, Normalizer.Form.NFKC)) },
            { listOf(it, toHiragana(it), codepoints(it) { c -> if (c in 0x3041..0x3096) c + 0x60 else c }) },
            { listOf(it, collapse(it, false), collapse(it, true)) },
            { listOf(it, codepoints(it) { c -> if (c in 48..57 || c in 65..90 || c in 97..122) c + 0xfee0 else c }) },
            { listOf(it, codepoints(it) { c -> kanji[c] ?: c }) },
            { listOf(it, iteration(it)) },
            { listOf(it, codepoints(it) { c -> if (c in 0xff10..0xff19) "〇一二三四五六七八九"[c - 0xff10].code else c }) },
        )
        for (processor in processors) { val next = linkedMapOf<String, Int>(); variants.forEach { (text, steps) -> processor(text).forEach { transformed -> val count = steps + if (transformed == text) 0 else 1; if (next[transformed]?.let { it <= count } != true) next[transformed] = count } }; variants = next }
        JapaneseLookup.romaji(value).forEach { variants[it] = 1 }
        return variants
    }
    private fun codepoints(value: String, map: (Int) -> Int) = buildString { value.codePoints().forEach { appendCodePoint(map(it)) } }
    private fun toHiragana(value: String) = buildString {
        value.codePoints().forEach { c -> if (c == 0x30fc && isNotEmpty()) append(vowel(last()) ?: 'ー') else appendCodePoint(if (c in 0x30a1..0x30f4) c - 0x60 else c) }
    }
    private fun vowel(c: Char): Char? = when {
        c in "ぁあかがさざただなはばぱまゃやらゎわヵァアカガサザタダナハバパマャヤラヮワヵヷ" -> 'あ'
        c in "ぃいきぎしじちぢにひびぴみりゐィイキギシジチヂニヒビピミリヰヸ" -> 'い'
        c in "ぅうくぐすずっつづぬふぶぷむゅゆるゥウクグスズッツヅヌフブプムュユルヴ" -> 'う'
        c in "ぇえけげせぜてでねへべぺめれゑヶェエケゲセゼテデネヘベペメレヱヶヹ" -> 'え'
        c in "ぉおこごそぞとどのほぼぽもょよろをォオコゴソゾトドノホボポモョヨロヲヺ" -> 'う'
        else -> null
    }
    private fun collapse(value: String, full: Boolean): String {
        val emphatic = "っッー"; val first = value.indexOfFirst { it !in emphatic }; val last = value.indexOfLast { it !in emphatic }; if (first < 0) return value
        return value.take(first) + buildString { var previous: Char? = null; for (c in value.substring(first, last + 1)) { if (c in emphatic) { if (!full && previous != c) append(c); previous = c } else { append(c); previous = null } } } + value.substring(last + 1)
    }
    private fun iteration(value: String): String {
        val points = value.codePoints().toArray(); return buildString { var i = 0; while (i < points.size) { val current = points[i++]; appendCodePoint(current); val next = points.getOrNull(i); if (next in listOf(0x3005, 0x309d, 0x30fd)) { appendCodePoint(current); i++ } else if (next in listOf(0x309e, 0x30fe)) { val voiced = Normalizer.normalize(String(Character.toChars(current)) + "\u3099", Normalizer.Form.NFC); append(if (voiced.codePointCount(0, voiced.length) == 1) voiced else String(Character.toChars(current))); i++ } } }
    }
}
