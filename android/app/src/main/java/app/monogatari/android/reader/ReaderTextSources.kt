package app.monogatari.android.reader

import android.text.Spanned

data class ReaderSourceSpan(val index:Int)
data class ReaderSourceRange(val index:Int,val start:Int,val end:Int)

/** Source page identities survive markup shaping and repagination; percentage/page counters are never source indices. */
object ReaderTextSources {
    fun scrollPageCount(contentHeight:Float,viewportHeight:Float)=if(viewportHeight>0f)kotlin.math.ceil(contentHeight/viewportHeight).toInt().coerceAtLeast(1) else 1
    fun scrollPage(position:Double,count:Int)=(position.coerceIn(0.0,1.0)*count.coerceAtLeast(1)).toInt().coerceIn(0,count.coerceAtLeast(1)-1)
    fun wrap(index:Int,html:String)="<section data-monogatari-source=\"$index\"><monogatarisource>$html</monogatarisource></section>"
    fun ranges(text:CharSequence):List<ReaderSourceRange> = (text as? Spanned)?.let { value ->
        value.getSpans(0,value.length,ReaderSourceSpan::class.java).map { span ->ReaderSourceRange(span.index,value.getSpanStart(span),value.getSpanEnd(span)) }.sortedBy { it.start }
    }.orEmpty()
    fun indices(text:CharSequence,pages:List<TextPage>):Set<Int> {
        val ranges=ranges(text)
        return ranges.filter { source ->pages.any { page ->source.start<page.end && source.end>page.start } }.map { it.index }.toSet()
    }
    fun descriptionIndices(visible:Set<Int>,available:Set<Int>,text:Boolean):Set<Int> {
        val exact=visible intersect available
        // The original text reader exposes the first source description on every generated page.
        return exact.ifEmpty { if(text)available.minOrNull()?.let(::setOf).orEmpty() else emptySet() }
    }
}
