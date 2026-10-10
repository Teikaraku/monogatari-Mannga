package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.roundToInt

/** ReaderPageView processes the full page before extracting either side of a spread. */
class DownsamplePageTransformation(private val crop: Boolean, private val half: Int?, private val width: Int) : Transformation {
    init { require(width > 0) }
    override val cacheKey = "monogatari-downsample-v2:$crop:$half:$width"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val prepared = PageTransformation(crop, null, 0).transform(input, size)
        val processed = if (prepared.width > width) {
            val height = (prepared.height.toDouble() * width / prepared.width).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(prepared, width, height, true)
        } else prepared
        return if (half == null) processed else PageTransformation(false, half, 0).transform(processed, size)
    }
}

object ReaderPageProcessing {
    fun transformation(context: Context, options: ReaderOptions, half: Int?, modelFile: String, best: Boolean,
        targetWidth: Int = context.resources.displayMetrics.widthPixels): Transformation? = when {
        // The original downsample branch takes precedence over the optional AI processor.
        options.downsample -> DownsamplePageTransformation(options.cropBorders, half, targetWidth)
        options.upscale -> AiUpscaleTransformation(context, options.cropBorders, half, options.upscaleMaxHeight, modelFile, best)
        options.cropBorders || half != null -> PageTransformation(options.cropBorders, half, 0)
        else -> null
    }
}
