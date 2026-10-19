package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

class AiUpscaleTransformation(private val context: Context, private val crop: Boolean, private val half: Int?,
    private val maxHeight: Int, private val modelFile: String, private val best: Boolean) : Transformation {
    override val cacheKey = "monogatari-original-ai-v4:$crop:$half:$maxHeight:$modelFile:$best"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val prepared = PageTransformation(crop, null, 0).transform(input, size)
        val processed=UpscaleModelManager.get(context).upscale(prepared,maxHeight,best)
        // ReaderPageView.splitImage crops the already-processed full image; each half is never fed back into the model.
        return if(half==null)processed else PageTransformation(false,half,0).transform(processed,size)
    }
}
