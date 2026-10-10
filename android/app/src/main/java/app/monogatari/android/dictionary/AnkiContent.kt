package app.monogatari.android.dictionary

import android.text.TextUtils

object AnkiContent {
    private fun html(value: String) = TextUtils.htmlEncode(value).replace("\n", "<br>")
    fun sentence(entry: VocabularyEntry, clozeSyntax: Boolean = false): String {
        val token = VocabularyCloze.range(entry.sentence, entry.cloze, entry.clozeOffset) ?: return html(entry.sentence)
        val target = html(entry.sentence.substring(token.first, token.last + 1))
        return html(entry.sentence.substring(0, token.first)) + (if (clozeSyntax) "{{c1::$target}}" else "<b>$target</b>") + html(entry.sentence.substring(token.last + 1))
    }
    fun values(entry: VocabularyEntry): Map<String, String> {
        val token = VocabularyCloze.range(entry.sentence, entry.cloze, entry.clozeOffset)
        return mapOf("expression" to html(entry.word), "reading" to html(entry.reading), "glossary" to html(entry.definition),
            "sentence" to sentence(entry), "sentence-plain" to html(entry.sentence), "sentence-cloze" to sentence(entry, true), "cloze" to html(entry.cloze),
            "cloze-offset" to token?.first?.toString().orEmpty(), "cloze-prefix" to token?.let { html(entry.sentence.substring(0, it.first)) }.orEmpty(),
            "cloze-suffix" to token?.let { html(entry.sentence.substring(it.last + 1)) }.orEmpty(), "page" to (entry.page + 1).toString())
    }
}
