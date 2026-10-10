package app.monogatari.android.reader

/** Convert a reader-frame gesture to the same cropped/split bitmap that OCR receives. */
object ReaderImageGeometry {
    fun language(chapterLanguage:String,sourceConfiguration:String?,mangaLanguage:String=""):String = chapterLanguage.ifBlank {
        runCatching { org.json.JSONObject(sourceConfiguration ?: "{}").let { configuration ->
            (configuration.optJSONObject("info") ?: configuration).optJSONArray("languages")?.optString(0).orEmpty()
        } }.getOrDefault("")
    }.ifBlank { mangaLanguage }
    fun normalizedTap(x:Float,y:Float,frameWidth:Float,frameHeight:Float,imageWidth:Float,imageHeight:Float,
        fit:String,zoom:Float=1f,panX:Float=0f,panY:Float=0f,layerWidth:Float=frameWidth,layerHeight:Float=frameHeight):Pair<Float,Float>? {
        if(minOf(frameWidth,frameHeight,imageWidth,imageHeight)<=0f)return null
        val factor=when(fit){"width"->frameWidth/imageWidth;"height"->frameHeight/imageHeight;"fill"->maxOf(frameWidth/imageWidth,frameHeight/imageHeight);else->minOf(frameWidth/imageWidth,frameHeight/imageHeight)}
        val width=imageWidth*factor;val height=imageHeight*factor;val scale=zoom.coerceAtLeast(1f)
        val tx=panX.coerceIn(-layerWidth*(scale-1)/2,layerWidth*(scale-1)/2);val ty=panY.coerceIn(-layerHeight*(scale-1)/2,layerHeight*(scale-1)/2)
        val localX=(x-frameWidth/2-tx)/scale+frameWidth/2;val localY=(y-frameHeight/2-ty)/scale+frameHeight/2
        val px=(localX-(frameWidth-width)/2)/width;val py=(localY-(frameHeight-height)/2)/height
        return if(px in 0f..1f && py in 0f..1f)px to py else null
    }
    fun lookupEnabled(enabled:Boolean,restrict:Boolean,language:String,languages:Set<String>,text:Boolean=false)=enabled && (text || !restrict || language.isEmpty() || language in languages)
}
