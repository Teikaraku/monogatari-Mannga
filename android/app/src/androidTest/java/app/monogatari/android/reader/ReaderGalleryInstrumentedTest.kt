package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderGalleryInstrumentedTest {
    @Test fun galleryWritesReadablePngAndPublishesScopedStorageEntry() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        if(Build.VERSION.SDK_INT<=28) {
            for(permission in listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                val output=android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                ).use { it.readBytes().toString(Charsets.UTF_8) }
                assertEquals("Storage grant failed: $output",android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(permission))
            }
            assertEquals(Environment.MEDIA_MOUNTED,Environment.getExternalStorageState())
        }
        val image=Bitmap.createBitmap(17,19,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(31,97,163))}
        val uri=ReaderImages.saveGallery(context,image,"Aidoku/gallery:test")
        try {
            val bytes=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
            assertEquals(0x89,bytes[0].toInt() and 255);assertEquals("PNG",String(bytes,1,3))
            val restored=BitmapFactory.decodeByteArray(bytes,0,bytes.size);assertEquals(17,restored.width);assertEquals(19,restored.height);assertEquals(image.getPixel(0,0),restored.getPixel(0,0))
            if(Build.VERSION.SDK_INT>=29)context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.IS_PENDING),null,null,null)!!.use{cursor->assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0))}
            else context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.DATA,android.provider.MediaStore.Images.Media.MIME_TYPE),null,null,null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst());val path=cursor.getString(0)
                val public=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath.trimEnd('/')+"/"
                assertTrue("MediaStore must publish a real public Pictures file, got $path",path.startsWith(public));assertFalse(path.startsWith(context.filesDir.absolutePath));assertEquals("image/png",cursor.getString(1))
            }
            restored.recycle()
        } finally { context.contentResolver.delete(uri,null,null);image.recycle() }
    }
}
