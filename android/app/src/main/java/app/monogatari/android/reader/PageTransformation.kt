package app.monogatari.android.reader

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Border removal and spread splitting use the decoded pixels and preserve the original file. */
class PageTransformation(private val crop: Boolean, private val half: Int?, private val upscaleHeight: Int) : Transformation {
    override val cacheKey = "monogatari-page-v2:$crop:$half:$upscaleHeight"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        var result = input
        if(crop)result=CropBordersProcessor.crop(result)
        if (half != null && result.width > result.height) {
            val cut = result.width / 2; val x = if (half == 0) 0 else cut
            result = Bitmap.createBitmap(result, x, 0, if (half == 0) cut else result.width - cut, result.height)
        }
        if (upscaleHeight > result.height && result.height > 0) {
            val height = minOf(upscaleHeight, result.height * 2)
            val width = (result.width.toLong() * height / result.height).toInt()
            if (width.toLong() * height <= 16_000_000) result = Bitmap.createScaledBitmap(result, width, height, true)
        }
        result
    }
}
