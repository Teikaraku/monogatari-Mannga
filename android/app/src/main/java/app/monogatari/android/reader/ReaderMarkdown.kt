package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.Html
import android.text.Spanned
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.jsoup.Jsoup
import java.io.File
import android.net.Uri
import app.monogatari.android.data.AppRepository

/** Markdown is parsed once; the same shaped spans, including images, are used for pagination and display. */
object ReaderMarkdown {
    private val extensions=listOf(TablesExtension.create(),StrikethroughExtension.create())
    private val parser=Parser.builder().extensions(extensions).build()
    private val renderer=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()
    fun html(markdown:String):String = renderer.render(parser.parse(markdown))

    suspend fun materialize(context:Context,markdown:String,load:suspend(String)->Bitmap):String {
        val doc=Jsoup.parseBodyFragment(html(markdown))
        doc.select("a[href]").forEach { link ->val value=link.attr("href");if(!value.startsWith('#') && Uri.parse(value).scheme !in setOf("http","https","mailto","file","content"))link.removeAttr("href") }
        for(image in doc.select("img[src]")) {
            val url=image.attr("src")
            try {
                require(Uri.parse(url).scheme in setOf("http","https","file","content","data","aidoku-archive")) { "Unsupported image URL" }
                val bitmap=load(url)
                val file=File(context.cacheDir,"reader-pages/markdown-${AppRepository.hash(url)}.png");file.parentFile?.mkdirs()
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                ReaderPageCache.touch(context,file);image.attr("src",Uri.fromFile(file).toString())
            } catch(error:Exception) {
                if(error is kotlinx.coroutines.CancellationException)throw error
                image.replaceWith(org.jsoup.nodes.TextNode("[${image.attr("alt").ifBlank { "Image" }}: ${error.message.orEmpty()}]"))
            }
        }
        return doc.body().html()
    }
    fun spans(context:Context,html:String,width:Int,height:Int,paragraphSpacingPixels:Int=0):Spanned {
        val doc=Jsoup.parseBodyFragment(html)
        // TextView's HTML renderer has no table layout; retain every cell in a readable fixed-width block.
        doc.select("table").forEach { table ->
            val rows=table.select("tr").joinToString("\n") { row ->row.select("th,td").joinToString("  |  ") { it.text() } }
            table.replaceWith(org.jsoup.nodes.Element("pre").text(rows))
        }
        doc.select("ol").forEach { list ->list.children().filter { it.tagName()=="li" }.forEachIndexed { index,item ->item.tagName("p").prependText("${index+1}. ") } }
        doc.select("del").forEach { it.tagName("strike") }
        doc.select("code").forEach { it.tagName("tt") }
        doc.select("pre").forEach { block ->
            val text=block.wholeText();block.tagName("p").html("<tt>"+org.jsoup.nodes.TextNode(text).outerHtml().replace(" ","&nbsp;").replace("\n","<br>")+"</tt>")
        }
        val getter=Html.ImageGetter { url ->
            val file=Uri.parse(url).takeIf { it.scheme=="file" }?.path?.let(::File)
            val allowed=File(context.cacheDir,"reader-pages").canonicalPath+File.separator
            if(file==null || !file.canonicalPath.startsWith(allowed))null else
                android.graphics.BitmapFactory.decodeFile(file.path)?.let { image ->
                    val scale=minOf(width.coerceAtLeast(32).toFloat()/image.width,height.coerceAtLeast(32).toFloat()/image.height,1f)
                    BitmapDrawable(context.resources,image).apply { setBounds(0,0,(image.width*scale).toInt().coerceAtLeast(1),(image.height*scale).toInt().coerceAtLeast(1)) }
                }
        }
        fun render(markup:String)=Html.fromHtml(markup,Html.FROM_HTML_MODE_LEGACY,getter,null)
        val sources=doc.select("[data-monogatari-source]")
        val result:Spanned=if(sources.isEmpty())render(doc.body().html()) else android.text.SpannableStringBuilder().apply {
            sources.forEach { section ->
                val index=section.attr("data-monogatari-source").toIntOrNull() ?: return@forEach
                // Html's TagSoup parser can auto-close/restart unknown inline tags around block elements.
                // Shape each source independently, then attach its identity to the final characters.
                section.select("monogatarisource").unwrap()
                var start=length;append(render(section.html()));var end=length
                while(start<end && this[start].isWhitespace())start++
                while(end>start && this[end-1].isWhitespace())end--
                if(end>start)setSpan(ReaderSourceSpan(index),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if(paragraphSpacingPixels<=0)return result
        return android.text.SpannableStringBuilder(result).apply {
            Regex("\n\n").findAll(toString()).forEach { match ->setSpan(android.text.style.LineHeightSpan { _,_,_,_,_,metrics ->
                metrics.descent+=paragraphSpacingPixels;metrics.bottom+=paragraphSpacingPixels
            },match.range.last,match.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
