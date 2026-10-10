package app.monogatari.android.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Original JPEG quality-zero/0.4 thumbnail scan, with an inclusive last content sample. */
object CropBordersProcessor {
    private const val SCALE=0.4

    internal fun contentBounds(image:Bitmap):Rect? {
        var left=image.width;var top=image.height;var right=-1;var bottom=-1
        val row=IntArray(image.width)
        for(y in 0 until image.height) {
            image.getPixels(row,0,image.width,0,y,image.width,1)
            for(x in row.indices) {
                val pixel=row[x];val alpha=Color.alpha(pixel)
                if(alpha==0)continue
                // CoreGraphics draws the scan into a premultiplied ARGB bitmap.
                val r=Color.red(pixel)*alpha/255;val g=Color.green(pixel)*alpha/255;val b=Color.blue(pixel)*alpha/255
                if(r>0xAA && g>0xAA && b>0xAA || r<0x05 && g<0x05 && b<0x05)continue
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
            }
        }
        return if(right<left || bottom<top)null else Rect(left,top,right+1,bottom+1)
    }

    fun crop(input:Bitmap):Bitmap {
        val encoded=ByteArrayOutputStream()
        if(!input.compress(Bitmap.CompressFormat.JPEG,0,encoded))return input
        val bytes=encoded.toByteArray()
        val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return input
        val maximum=(maxOf(input.width,input.height)*SCALE).roundToInt().coerceAtLeast(1)
        val ratio=maximum.toDouble()/maxOf(decoded.width,decoded.height)
        val width=(decoded.width*ratio).roundToInt().coerceAtLeast(1)
        val height=(decoded.height*ratio).roundToInt().coerceAtLeast(1)
        val sampled=Bitmap.createScaledBitmap(decoded,width,height,true)
        try {
            val bounds=contentBounds(sampled) ?: return input
            // CGImage.cropping uses the integral rectangle and intersects it with the original image.
            val left=floor(bounds.left/SCALE).toInt().coerceIn(0,input.width)
            val top=floor(bounds.top/SCALE).toInt().coerceIn(0,input.height)
            val right=ceil(bounds.right/SCALE).toInt().coerceIn(0,input.width)
            val bottom=ceil(bounds.bottom/SCALE).toInt().coerceIn(0,input.height)
            if(right<=left || bottom<=top || left==0 && top==0 && right==input.width && bottom==input.height)return input
            return Bitmap.createBitmap(input,left,top,right-left,bottom-top)
        } finally {
            if(sampled!==decoded)sampled.recycle()
            decoded.recycle()
        }
    }
}
