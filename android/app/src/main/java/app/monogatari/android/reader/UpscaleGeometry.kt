package app.monogatari.android.reader

/** Shape constraints protect actual tensor/bitmap allocations, rather than imposing a model-format size limit. */
object UpscaleGeometry {
    const val MAX_PIXELS=32_000_000L
    fun checkPixels(width:Int,height:Int) {
        require(width>0 && height>0 && width.toLong()*height<=MAX_PIXELS) { "模型图像超出内存限制（最多 3200 万像素）" }
    }
    fun scaledDimensions(width:Int,height:Int,scale:Int):Pair<Int,Int> {
        require(scale>0) { "模型倍率必须为正整数" }
        val w=width.toLong()*scale;val h=height.toLong()*scale
        require(w in 1..Int.MAX_VALUE.toLong() && h in 1..Int.MAX_VALUE.toLong()) { "超分输出尺寸溢出" }
        checkPixels(w.toInt(),h.toInt());return w.toInt() to h.toInt()
    }
    fun validate(model:UpscaleModel)=validate(model.processingMode,model.inputWidth,model.inputHeight,model.outputWidth,model.outputHeight,model.blockSize,model.shrinkSize,model.scale,model.overlap)
    fun validate(mode:String,inputWidth:Int,inputHeight:Int,outputWidth:Int,outputHeight:Int,block:Int,shrink:Int,scale:Int,overlap:Int) {
        checkPixels(inputWidth,inputHeight);checkPixels(outputWidth,outputHeight)
        require(mode=="image" || mode=="multiarray") { "未知模型处理模式：$mode" }
        if(mode=="image")return
        require(block>0 && inputWidth==block && inputHeight==block) { "分块模型需要固定正方形输入" }
        val core=block.toLong()-2L*shrink
        require(shrink>=0 && core>0 && scale>0 && overlap>=0 && overlap.toLong()<core && core*scale==outputWidth.toLong() && outputWidth==outputHeight) { "模型输出尺寸与 scale / shrinkSize / tileOverlap 不匹配" }
    }
}
