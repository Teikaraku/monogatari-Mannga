package app.monogatari.android.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropBordersTest {
    @Test fun scanIgnoresTransparentMixedWhiteBlackAndUsesStrictOriginalThresholds() {
        val image=Bitmap.createBitmap(7,5,Bitmap.Config.ARGB_8888)
        for(x in 0 until image.width)for(y in 0 until image.height)
            image.setPixel(x,y,when((x+y)%3){0->Color.WHITE;1->Color.BLACK;else->Color.TRANSPARENT})
        assertNull(CropBordersProcessor.contentBounds(image))
        image.setPixel(1,1,Color.rgb(0xAA,0xAA,0xAA));image.setPixel(2,2,Color.rgb(0x05,0x05,0x05))
        image.setPixel(6,4,Color.BLUE)
        assertEquals(Rect(1,1,7,5),CropBordersProcessor.contentBounds(image))
        image.recycle()
    }

    @Test fun singleLastColumnAndLargeBordersRetainAllContentSampleCells() {
        val image=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        image.setPixel(199,100,Color.RED)
        assertEquals(Rect(199,100,200,101),CropBordersProcessor.contentBounds(image))
        image.eraseColor(Color.WHITE)
        for(x in 98..101)for(y in 97..102)image.setPixel(x,y,Color.BLUE)
        assertEquals(Rect(98,97,102,103),CropBordersProcessor.contentBounds(image))
        image.setPixel(0,0,Color.RED)
        assertEquals(Rect(0,0,102,103),CropBordersProcessor.contentBounds(image))
        image.recycle()
    }

    @Test fun actualJpegThumbnailBoundsCropOriginalPixelsForColorTransparentAndMixedBorders() {
        for(kind in 0..4) {
            val thin=kind==4
            val image=Bitmap.createBitmap(if(thin)120 else 160,if(thin)80 else 120,Bitmap.Config.ARGB_8888).apply { eraseColor(if(kind==1)Color.TRANSPARENT else Color.WHITE) }
            if(kind==2)for(x in 0 until image.width)for(y in 0 until image.height)if(x<40 || x>=120)image.setPixel(x,y,Color.BLACK)
            for(x in (if(thin)10 else 60) until (if(thin)110 else 100))for(y in (if(thin)10 else 45) until (if(thin)70 else 75))
                image.setPixel(x,y,if(x<image.width/2)Color.RED else Color.BLUE)
            if(kind==3)for(x in 0 until 8)for(y in 0 until 8)image.setPixel(x,y,Color.rgb(80,120,200))
            // Independent JPEG/thumbnail reference. The literal RGB predicate and inclusive maxima are the original scan contract.
            val bytes=ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.JPEG,0,it)) }.toByteArray()
            val jpeg=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            val sample=Bitmap.createScaledBitmap(jpeg,if(thin)48 else 64,if(thin)32 else 48,true)
            var minX=sample.width;var minY=sample.height;var maxX=-1;var maxY=-1
            for(y in 0 until sample.height)for(x in 0 until sample.width) {
                val color=sample.getPixel(x,y);val rgb=listOf(Color.red(color),Color.green(color),Color.blue(color))
                if(Color.alpha(color)!=0 && !rgb.all{it>170} && !rgb.all{it<5}) {
                    minX=minOf(minX,x);minY=minOf(minY,y);maxX=maxOf(maxX,x);maxY=maxOf(maxY,y)
                }
            }
            assertTrue(maxX>=minX && maxY>=minY)
            val rectangle=Rect(floor(minX/0.4).toInt(),floor(minY/0.4).toInt(),
                ceil((maxX+1)/0.4).toInt().coerceAtMost(image.width),ceil((maxY+1)/0.4).toInt().coerceAtMost(image.height))
            val output=CropBordersProcessor.crop(image)
            assertEquals(rectangle.width(),output.width);assertEquals(rectangle.height(),output.height)
            for(x in 0 until output.width)for(y in 0 until output.height)
                assertEquals(image.getPixel(x+rectangle.left,y+rectangle.top),output.getPixel(x,y))
            if(output!==image)output.recycle();if(sample!==jpeg)sample.recycle();jpeg.recycle();image.recycle()
        }
    }

    @Test fun uniformlyWhiteBlackAndTransparentPagesKeepTheOriginalImage() {
        val image=Bitmap.createBitmap(30,20,Bitmap.Config.ARGB_8888)
        for(color in listOf(Color.WHITE,Color.BLACK,Color.TRANSPARENT)) {
            image.eraseColor(color);assertSame(image,CropBordersProcessor.crop(image))
        }
        image.recycle()
    }
}
