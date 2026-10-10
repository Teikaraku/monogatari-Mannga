package app.monogatari.android.sources

import android.graphics.*
import okhttp3.Request
import java.io.File
import kotlin.math.PI

/** Native Android Canvas is the counterpart of the source SDK's CoreGraphics imports. */
internal class SourceCanvas(private val host: SourceHost) {
    private val fontNames by lazy { (setOf("sans-serif", "serif", "monospace", "cursive", "sans-serif-light", "sans-serif-medium", "sans-serif-condensed", "sans-serif-thin", "sans-serif-black") + runCatching { Regex("<(?:family|alias)\\s+name=\"([^\"]+)\"").findAll(File("/system/etc/fonts.xml").readText()).map { it.groupValues[1] }.toSet() }.getOrDefault(emptySet())).associateBy { it.lowercase() } }
    private class Surface(val bitmap: Bitmap, val canvas: Canvas = Canvas(bitmap))
    private fun f(n: Long) = java.lang.Float.intBitsToFloat(n.toInt())
    private fun fbits(n: Float) = java.lang.Float.floatToRawIntBits(n).toLong()
    private fun color(r: Float, g: Float, b: Float, a: Float) = Color.argb((a * 255).toInt().coerceIn(0,255), (r * 255).toInt().coerceIn(0,255), (g * 255).toInt().coerceIn(0,255), (b * 255).toInt().coerceIn(0,255))
    private fun bitmap(ref: Long): Bitmap? = when (val value = host.get(ref.toInt())) { is Bitmap -> value; is ByteArray -> BitmapFactory.decodeByteArray(value, 0, value.size); else -> null }
    fun call(name: String, a: LongArray): Long {
        fun store(value: Any?) = host.store(value).toLong()
        if (name == "new_context") { val w=f(a[0]).toInt(); val h=f(a[1]).toInt(); if (w <= 0 || h <= 0 || w.toLong()*h > 16_000_000) return -6; return store(Surface(Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888))) }
        if (name == "new_image") { val bytes=runCatching { host.data(a[0],a[1]) }.getOrNull() ?: return -11; val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds); if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong()*bounds.outHeight > 16_000_000) return -3; return store(BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return -3) }
        if (name == "get_image_data") { val image=host.get(a[0].toInt()); if (image !is Bitmap && image !is ByteArray) return -2; return store(runCatching { host.imageBytes(image) }.getOrNull() ?: return -3) }
        if (name == "get_image_width") return fbits(bitmap(a[0])?.width?.toFloat() ?: -2f)
        if (name == "get_image_height") return fbits(bitmap(a[0])?.height?.toFloat() ?: -2f)
        if (name == "new_font") { val font=runCatching { host.string(a[0],a[1]) }.getOrNull() ?: return -9; val family=fontNames[font.lowercase()] ?: when(font.lowercase()) { "arial", "helvetica", "helveticaneue", "system" -> "sans-serif"; "timesnewromanpsmt", "times new roman", "times" -> "serif"; "couriernewpsmt", "courier new", "courier" -> "monospace"; else -> return -10 }; return store(Typeface.create(family,Typeface.NORMAL)) }
        if (name == "system_font") { val weight = listOf(100,200,300,400,500,600,700,800,900).getOrElse(a[0].toInt()) { 400 }; return store(if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT,weight,false) else Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)) }
        if (name == "load_font") { val address=runCatching { host.string(a[0],a[1]) }.getOrNull() ?: return -9; return runCatching { val bytes=host.fetch(Request.Builder().url(address).build()).bytes; val file=File(host.context.cacheDir,"source-font-${SourceRuntimeManager.digest(bytes.contentHashCode().toString())}"); file.writeBytes(bytes); store(Typeface.createFromFile(file)) }.getOrDefault(-12) }
        val surface=host.get(a[0].toInt()) as? Surface ?: return -1
        val canvas=surface.canvas
        return when (name) {
            "set_transform" -> { val matrix=Matrix(); matrix.postRotate((f(a[5])*180/PI).toFloat()); matrix.postScale(f(a[3]),f(a[4])); matrix.postTranslate(f(a[1]),f(a[2])); canvas.setMatrix(matrix); 0 }
            "draw_image" -> { val image=bitmap(a[1]) ?: return -2; canvas.drawBitmap(image,null,RectF(f(a[2]),f(a[3]),f(a[2])+f(a[4]),f(a[3])+f(a[5])),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); 0 }
            "copy_image" -> { val image=bitmap(a[1]) ?: return -2; val src=Rect(f(a[2]).toInt(),f(a[3]).toInt(),(f(a[2])+f(a[4])).toInt(),(f(a[3])+f(a[5])).toInt()); if (src.left < 0 || src.top < 0 || src.right > image.width || src.bottom > image.height || src.isEmpty) return -4; val dst=RectF(f(a[6]),f(a[7]),f(a[6])+f(a[8]),f(a[7])+f(a[9])); canvas.drawBitmap(image,src,dst,Paint(Paint.ANTI_ALIAS_FLAG)); 0 }
            "get_image" -> store(surface.bitmap.copy(Bitmap.Config.ARGB_8888,false))
            "fill" -> { val path=runCatching { path(PostcardReader(host.resultData(a[1].toInt()))) }.getOrNull() ?: return -7; val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; color=color(f(a[2]),f(a[3]),f(a[4]),f(a[5])) }; canvas.drawPath(path,paint); 0 }
            "stroke" -> { val path=runCatching { path(PostcardReader(host.resultData(a[1].toInt()))) }.getOrNull() ?: return -7; val paint=runCatching { val r=PostcardReader(host.resultData(a[2].toInt())); Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=color(r.float(),r.float(),r.float(),r.float()); strokeWidth=r.float(); strokeCap=listOf(Paint.Cap.ROUND,Paint.Cap.SQUARE,Paint.Cap.BUTT)[r.byte()]; strokeJoin=listOf(Paint.Join.ROUND,Paint.Join.BEVEL,Paint.Join.MITER)[r.byte()]; strokeMiter=r.float(); val dash=r.list { r.float() }; val offset=r.float(); if (dash.isNotEmpty()) pathEffect=DashPathEffect(dash.toFloatArray(),offset) } }.getOrNull() ?: return -8; canvas.drawPath(path,paint); 0 }
            "draw_text" -> { val font=host.get(a[6].toInt()) as? Typeface ?: return -10; val text=runCatching { host.string(a[1],a[2]) }.getOrNull() ?: return -9; val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=f(a[3]); typeface=font; color=color(f(a[7]),f(a[8]),f(a[9]),f(a[10])) }; val bounds=Rect(); paint.getTextBounds(text,0,text.length,bounds); canvas.drawText(text,f(a[4]),f(a[5])+bounds.height(),paint); 0 }
            else -> error("Unknown canvas import $name")
        }
    }
    private fun path(r: PostcardReader): Path {
        val path=Path()
        repeat(r.count()) { when (r.byte()) {
            0 -> path.moveTo(r.float(),r.float())
            1 -> path.lineTo(r.float(),r.float())
            2 -> { val x=r.float(); val y=r.float(); val cx=r.float(); val cy=r.float(); path.quadTo(cx,cy,x,y) }
            3 -> { val x=r.float(); val y=r.float(); val cx=r.float(); val cy=r.float(); val cx2=r.float(); val cy2=r.float(); path.cubicTo(cx,cy,cx2,cy2,x,y) }
            4 -> { val x=r.float(); val y=r.float(); val radius=r.float(); val start=r.float()*180/PI; val signedEnd=r.float()*180/PI; var sweep=(kotlin.math.abs(signedEnd)-start).toFloat(); if(signedEnd>=0) { while(sweep>0) sweep-=360 } else { while(sweep<0) sweep+=360 }; path.arcTo(RectF(x-radius,y-radius,x+radius,y+radius),start.toFloat(),sweep.coerceIn(-360f,360f)) }
            5 -> path.close()
            else -> error("Invalid source canvas path")
        } }; return path
    }
}
