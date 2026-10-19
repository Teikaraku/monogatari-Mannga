package app.monogatari.android.local

/** Ordered ICU-compatible original series/volume/chapter grammar, including duplicate markers, ranges and part b. */
object LocalFileNameParser {
    // Android ICU already treats character classes as Unicode, but older libcore rejects Java's flag 256.
    // Probe only the flag with known-valid syntax; actual grammar compilation errors still propagate.
    private val unicodeClassFlag=try {
        java.util.regex.Pattern.compile("",java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
        java.util.regex.Pattern.UNICODE_CHARACTER_CLASS
    } catch(_:IllegalArgumentException) { 0 }
    private fun regex(value:String)=java.util.regex.Pattern.compile(value,
        java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE or unicodeClassFlag).toRegex()
    private val series=LocalFileNamePatterns.mangaSeriesPatterns.map(::regex)
    private val volumes=LocalFileNamePatterns.mangaVolumePatterns.map(::regex)
    private val chapters=LocalFileNamePatterns.mangaChapterPatterns.map(::regex)
    fun parseMangaSeries(filename:String)=first(series,filename,"Series")?.first?.let(::cleanTitle).orEmpty()
    fun parseMangaVolume(filename:String)=first(volumes,removeDuplicate(filename,true),"Volume")?.let { format(it.first,it.second) }.orEmpty()
    fun parseMangaChapter(filename:String)=first(chapters,removeDuplicate(filename,false),"Chapter")?.let { format(it.first,it.second) }.orEmpty()
    fun volumeNumber(filename:String)=parseMangaVolume(filename).substringBefore('-').toFloatOrNull()?.toDouble()?.takeIf(Double::isFinite)
    fun chapterNumber(filename:String)=parseMangaChapter(filename).substringBefore('-').toFloatOrNull()?.toDouble()?.takeIf(Double::isFinite)
    private fun first(patterns:List<Regex>,text:String,name:String):Pair<String,Boolean>? {
        for(pattern in patterns)for(match in pattern.findAll(text)) {
            val value=runCatching { match.groups[name]?.value }.getOrNull()
            if(!value.isNullOrEmpty())return value to (runCatching { match.groups["Part"] }.getOrNull()!=null)
        };return null
    }
    private fun format(value:String,part:Boolean):String {
        fun part(v:String)=if(part && '.' !in v)"$v.5" else v
        if('-' !in value)return zeroes(part(value))
        val tokens=value.split('-');val from=zeroes(tokens.first())
        if(tokens.size!=2)return from
        return from+"-"+zeroes(part(tokens[1].let { if(it.startsWith('c',true))it.drop(1) else it }))
    }
    private fun zeroes(value:String)=value.dropWhile { it=='0' }.ifEmpty { "0" }
    private fun cleanTitle(value:String):String = regex(LocalFileNamePatterns.emptySpaceRegex).replace(
        regex(LocalFileNamePatterns.mangaEditionRegex).replace(regex(LocalFileNamePatterns.cleanupRegex).replace(value.replace('_',' '),""),"")
            .trim('\u0000','\t','\r',' ','-',',')," ")
    private fun removeDuplicate(filename:String,volume:Boolean):String {
        val range=regex(if(volume)LocalFileNamePatterns.volumeRangeRegex else LocalFileNamePatterns.chapterRangeRegex)
        if(range.containsMatchIn(filename))return filename
        val duplicate=regex(if(volume)LocalFileNamePatterns.duplicateVolumeRegex else LocalFileNamePatterns.duplicateChapterRegex).find(filename) ?: return filename
        val start=duplicate.groups[1]?.range?.first ?: return filename
        val number=regex(if(volume)LocalFileNamePatterns.volumeNumberRegex else LocalFileNamePatterns.chapterNumberRegex)
        val first=number.find(filename,start) ?: return filename
        val second=number.find(filename,first.range.last+1) ?: return filename
        // JVM ranges and Strings both use UTF-16 offsets, including Thai/Russian/emoji prefixes.
        return filename.substring(0,second.range.first).trim(' ','-','_')
    }
}
