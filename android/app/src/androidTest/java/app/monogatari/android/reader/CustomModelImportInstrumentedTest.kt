package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import kotlin.math.abs
import coil.size.Size

@RunWith(AndroidJUnit4::class)
class CustomModelImportInstrumentedTest {
    @Test fun rectangularGrayImageAndFiveTimesMultiArrayModelsExecuteTheirFixedGraphs() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val manager=UpscaleModelManager.get(context);val previous=manager.enabledFile
        val input=Bitmap.createBitmap(7,5,Bitmap.Config.ARGB_8888)
        for(y in 0 until input.height)for(x in 0 until input.width)input.setPixel(x,y,Color.rgb(20+x*11+y*3,100+y*13+x*2,200-x*7-y*9))
        val proof=mutableListOf<String>()
        try {
            for(name in listOf("custom-gray-image","custom-rect-image","custom-scale5")) {
                val file=File(context.cacheDir,"$name.aidoku-model.zip");assets.open("custom-models/${file.name}").use { source ->file.outputStream().use { target ->source.copyTo(target) } }
                val model=manager.import(Uri.fromFile(file));manager.enable(model)
                val imageMode=name!="custom-scale5"
                assertEquals(if(imageMode)"image" else "multiarray",model.processingMode)
                assertEquals(if(imageMode)7 else 3,model.inputWidth);assertEquals(if(imageMode)5 else 3,model.inputHeight)
                if(!imageMode){assertEquals(5,model.scale);assertEquals(3,model.blockSize)}
                val output=manager.upscale(input,2000,true)
                val width=when(name){"custom-gray-image"->42;"custom-rect-image"->10;else->35}
                val height=when(name){"custom-gray-image"->15;"custom-rect-image"->8;else->25}
                assertEquals(width,output.width);assertEquals(height,output.height)
                var maximum=0
                for(y in 0 until height)for(x in 0 until width) {
                    val expected=when(name) {
                        "custom-gray-image"->{val px=input.getPixel(x/6,y/3);val gray=(Color.red(px)*.2126f+Color.green(px)*.7152f+Color.blue(px)*.0722f).toInt();intArrayOf(gray,gray,gray)}
                        "custom-rect-image"->{
                            val sx=x*7f/10;val sy=y*5f/8;val left=sx.toInt();val top=sy.toInt();val fx=sx-left;val fy=sy-top
                            val a=input.getPixel(left,top);val b=input.getPixel((left+1).coerceAtMost(6),top);val c=input.getPixel(left,(top+1).coerceAtMost(4));val d=input.getPixel((left+1).coerceAtMost(6),(top+1).coerceAtMost(4))
                            fun channel(extract:(Int)->Int)=((extract(a)*(1-fx)+extract(b)*fx)*(1-fy)+(extract(c)*(1-fx)+extract(d)*fx)*fy).toInt()
                            intArrayOf(channel(Color::red),channel(Color::green),channel(Color::blue))
                        }
                        else->{val px=input.getPixel(x/5,y/5);intArrayOf(Color.red(px),Color.green(px),Color.blue(px))}
                    }
                    val actual=output.getPixel(x,y);maximum=maxOf(maximum,abs(Color.red(actual)-expected[0]),abs(Color.green(actual)-expected[1]),abs(Color.blue(actual)-expected[2]))
                }
                assertTrue("$name must execute converted model pixels, max error=$maximum",maximum<=1)
                proof+="$name: actual model ZIP imported; ${input.width}x${input.height} -> ${width}x${height}; independent whole-image/tile pixel reference maxError=$maximum"
                if(imageMode)for(half in 0..1) {
                    val split=AiUpscaleTransformation(context,false,half,2000,model.file,false).transform(input,Size.ORIGINAL)
                    val cut=output.width/2;val offset=if(half==0)0 else cut
                    assertEquals(if(half==0)cut else output.width-cut,split.width);assertEquals(output.height,split.height)
                    for(y in 0 until split.height)for(x in 0 until split.width)assertEquals("$name half$half must crop the same complete processed tensor",output.getPixel(offset+x,y),split.getPixel(x,y))
                    split.recycle();proof+="$name half$half: full-image inference then actual ${if(half==0)cut else output.width-cut}x${output.height} crop, all pixels equal complete output"
                }
                output.recycle()
                if(name=="custom-gray-image") {
                    val large=Bitmap.createBitmap(14,10,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160,80,20)) }
                    val fixed=manager.upscale(large,2000,false)
                    assertEquals(42,fixed.width);assertEquals(15,fixed.height)
                    val gray=(160*.2126f+80*.7152f+20*.0722f).toInt()
                    for(y in 0 until fixed.height)for(x in 0 until fixed.width)assertTrue(abs(Color.red(fixed.getPixel(x,y))-gray)<=1)
                    proof+="Gray image mode: a different 14x10 source is scaleFill-resized once to 7x5 and remains fixed 42x15 output, not a tiled 84x30 result"
                    large.recycle();fixed.recycle()
                }
                manager.delete(model);file.delete()
            }
            File(context.getExternalFilesDir(null),"verification").apply{mkdirs()}.resolve("image-model-shapes.txt").writeText(proof.joinToString("\n"))
        } finally { manager.enable(manager.models().firstOrNull { it.file==previous });input.recycle() }
    }
    @Test fun fp16QuantizedAndMilCoreMlPackagesImportAndRenderExactPixels() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val manager=UpscaleModelManager.get(context);val previous=manager.enabledFile
        val input=Bitmap.createBitmap(21,19,Bitmap.Config.ARGB_8888)
        for(y in 0 until input.height)for(x in 0 until input.width)input.setPixel(x,y,Color.rgb((x*17+y*3)%256,(y*23+x*2)%256,(x*7+y*13)%256))
        val proof=mutableListOf<String>()
        try {
            for(name in listOf("custom-fp16-nn","custom-quant4-nn","custom-mil","named-input-output")) {
                val file=File(context.cacheDir,"$name.aidoku-model.zip");assets.open("custom-models/${file.name}").use { source ->file.outputStream().use { target ->source.copyTo(target) } }
                val model=manager.import(Uri.fromFile(file))
                assertEquals(16,model.blockSize);assertEquals(2,model.scale);assertFalse(model.inputHalfStep);assertFalse(model.preserveGrayscale)
                if(name=="named-input-output"){assertEquals("pixels",model.inputName);assertEquals("restored",model.outputName)}
                manager.enable(model)
                val output=manager.upscale(input,2000,false)
                assertEquals(42,output.width);assertEquals(38,output.height)
                var maximum=0
                for(y in 0 until output.height)for(x in 0 until output.width) {
                    val expected=input.getPixel(x/2,y/2);val actual=output.getPixel(x,y);val factor=if(name=="custom-mil").5f else 1f
                    maximum=maxOf(maximum,abs(Color.red(actual)-(Color.red(expected)*factor).toInt()),abs(Color.green(actual)-(Color.green(expected)*factor).toInt()),abs(Color.blue(actual)-(Color.blue(expected)*factor).toInt()))
                }
                assertTrue("$name must preserve its converted CoreML graph, maximum channel error=$maximum",maximum<=1)
                proof+="$name: actual ZIP import, FP32 NCHW runtime, 21x19 -> 42x38, all pixels matched, maximum channel error=$maximum"
                if(name=="custom-fp16-nn") {
                    val blended=File(context.cacheDir,"overlap-model.zip")
                    ZipFile(file).use { zip ->ZipOutputStream(blended.outputStream()).use { out ->zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name));val bytes=zip.getInputStream(entry).use{it.readBytes()}
                        out.write(if(entry.name=="model.json")JSONObject(bytes.toString(Charsets.UTF_8)).put("tileOverlap",8).toString().toByteArray() else bytes);out.closeEntry()
                    } } }
                    val overlap=manager.import(Uri.fromFile(blended));assertEquals(8,overlap.overlap);manager.enable(overlap)
                    val best=manager.upscale(input,2000,true)
                    for(y in 0 until best.height)for(x in 0 until best.width)assertEquals(output.getPixel(x,y),best.getPixel(x,y))
                    proof+="Custom best overlap8: original cosine row/column blending preserved nearest graph pixels at all tile boundaries"
                    manager.delete(overlap)
                }
                manager.delete(model);file.delete()
            }
            File(context.getExternalFilesDir(null),"verification").apply{mkdirs()}.resolve("custom-model-import.txt").writeText(proof.joinToString("\n"))
        } finally { manager.enable(manager.models().firstOrNull { it.file==previous });input.recycle() }
    }
}
