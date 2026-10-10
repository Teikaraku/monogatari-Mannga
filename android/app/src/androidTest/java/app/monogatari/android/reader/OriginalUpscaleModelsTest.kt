package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OriginalUpscaleModelsTest {
    @Test fun bothOriginalCoreMlWeightSetsExecuteOnAndroidCpu() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UpscaleModelManager.get(context)
        val old = manager.enabledFile
        val input = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 48) input.setPixel(x,y,Color.rgb(x*5,y*5,if ((x+y)%4==0) 255 else 20))
        val proof = Bitmap.createBitmap(512, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(proof); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=18f }
        val record = mutableListOf<String>()
        val fixture=JSONObject(context.assets.open("upscale/validation.json").bufferedReader().use { it.readText() })
        try {
            manager.models().filter { it.bundled }.forEachIndexed { index, model ->
                manager.enable(model)
                val start=System.nanoTime(); val output=manager.upscale(input,2000,false)
                assertEquals(96,output.width);assertEquals(96,output.height)
                val interpolation=Bitmap.createScaledBitmap(input,96,96,true)
                val actual=IntArray(96*96);val baseline=IntArray(96*96)
                output.getPixels(actual,0,96,0,0,96,96);interpolation.getPixels(baseline,0,96,0,0,96,96)
                val changed=actual.indices.count { actual[it] != baseline[it] }
                assertTrue("AI output must differ from interpolation",changed>100)
                val samples=fixture.getJSONObject(model.file).getJSONArray("samples")
                var largestError=0
                for(i in 0 until samples.length()) { val sample=samples.getJSONArray(i);val color=output.getPixel(sample.getInt(0),sample.getInt(1))
                    largestError=maxOf(largestError,abs(Color.red(color)-sample.getInt(2)),abs(Color.green(color)-sample.getInt(3)),abs(Color.blue(color)-sample.getInt(4))) }
                assertTrue("Android CPU must match independent desktop graph inference within one 8-bit step",largestError<=1)
                record += "${model.file}: 48x48 -> 96x96, original weight hash=${model.sha256}, differing pixels=$changed, elapsedMs=${(System.nanoTime()-start)/1_000_000}"
                record += "Reference RGB/NCHW normalization, half-step bias, edge padding and UInt8 truncation matched; maximum reference channel error=$largestError"
                val top=index*350f
                canvas.drawText(model.name,16f,top+22f,paint)
                canvas.drawBitmap(input,null,android.graphics.RectF(16f,top+40f,160f,top+184f),null)
                canvas.drawBitmap(output,null,android.graphics.RectF(190f,top+40f,478f,top+328f),null)
            }
            val folder=File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
            File(folder,"original-ai-models.png").outputStream().use { proof.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"original-ai-models.txt").writeText(record.joinToString("\n"))
        } finally { manager.enable(manager.models().firstOrNull { it.file==old }) }
    }
}
