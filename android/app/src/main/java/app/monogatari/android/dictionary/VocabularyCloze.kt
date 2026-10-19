package app.monogatari.android.dictionary

/** Original offsets count UTF-16 units, including emoji; matching tokens stay protected while editing. */
object VocabularyCloze {
    data class Context(val sentence: String, val offset: Int?)
    fun range(sentence: String, text: String, offset: Int?): IntRange? {
        if (offset == null || offset < 0 || text.isEmpty() || offset > sentence.length - text.length) return null
        val end = offset + text.length
        fun boundary(index: Int) = index == 0 || index == sentence.length || !(sentence[index].isLowSurrogate() && sentence[index - 1].isHighSurrogate())
        if (!boundary(offset) || !boundary(end) || !sentence.regionMatches(offset, text, 0, text.length)) return null
        return offset until end
    }
    fun capture(sentence: String, text: String, preferredOffset: Int? = null): Context {
        val offset = preferredOffset?.takeIf { range(sentence, text, it) != null }
            ?: if (preferredOffset == null && text.isNotEmpty()) sentence.indexOf(text).takeIf { it >= 0 } else null
        if (offset == null || offset <= 32767) return Context(sentence, offset)
        // Core Data's Int16 cannot hold a full-page offset. Retain the actual target in nearby context.
        var first = (offset - 512).coerceAtLeast(0)
        var end = (offset + maxOf(text.length, 512)).coerceAtMost(sentence.length)
        if (first > 0 && sentence[first].isLowSurrogate() && sentence[first - 1].isHighSurrogate()) first--
        if (end < sentence.length && sentence[end].isLowSurrogate() && sentence[end - 1].isHighSurrogate()) end++
        return Context(sentence.substring(first, end), offset - first)
    }
    fun edit(sentence: String, text: String, offset: Int?, replacement: String, selectionStart: Int? = null, selectionEnd: Int? = null): Context? {
        val token = range(sentence, text, offset) ?: return Context(replacement, offset)
        if (sentence == replacement) return Context(sentence, offset)
        var prefix = 0
        while (prefix < minOf(sentence.length, replacement.length) && sentence[prefix] == replacement[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(sentence.length, replacement.length) - prefix && sentence[sentence.lastIndex - suffix] == replacement[replacement.lastIndex - suffix]) suffix++
        var removedEnd = sentence.length - suffix
        var insertedLength = replacement.length - prefix - suffix
        if (selectionStart != null && selectionEnd != null && selectionStart in 0..sentence.length && selectionEnd in 0..sentence.length) {
            val first = minOf(selectionStart, selectionEnd); val last = maxOf(selectionStart, selectionEnd); val delta = replacement.length - sentence.length
            val possible = mutableListOf(first to last)
            if (first == last && delta < 0) { if (first + delta >= 0) possible += first + delta to first; if (last - delta <= sentence.length) possible += last to last - delta }
            possible.firstOrNull { (start, end) ->
                val length = delta + end - start
                length >= 0 && replacement.startsWith(sentence.substring(0, start)) && replacement.substring(start + length) == sentence.substring(end)
            }?.let { (start, end) -> prefix = start; removedEnd = end; insertedLength = delta + end - start }
        }
        val start = token.first; val end = token.last + 1
        if (prefix < end && removedEnd > start || prefix == removedEnd && prefix > start && prefix < end) return null
        val updatedOffset = if (removedEnd <= start) start + insertedLength - (removedEnd - prefix) else start
        if (updatedOffset !in 0..32767 || range(replacement, text, updatedOffset) == null) return null
        return Context(replacement, updatedOffset)
    }
    fun selection(start: Int, end: Int, range: IntRange?): Pair<Int, Int> {
        if (range != null && (minOf(start, end) < range.last + 1 && maxOf(start, end) > range.first || start == end && start > range.first && start <= range.last)) return range.first to range.first
        return start to end
    }
}
