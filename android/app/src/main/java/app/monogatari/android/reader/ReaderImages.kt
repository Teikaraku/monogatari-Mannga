package app.monogatari.android.reader

import app.monogatari.android.AppBrand

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object ReaderImages {
    fun saveGallery(context:Context,image:Bitmap,title:String):Uri {
        if(Build.VERSION.SDK_INT<=28) {
            check(context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "Storage permission is required to save to the public gallery" }
            check(Environment.getExternalStorageState()==Environment.MEDIA_MOUNTED) { "Public gallery storage is not mounted" }
        }
        val name=title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(80).ifBlank{AppBrand.NAME}+"-${System.currentTimeMillis()}.png"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            if(Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/${AppBrand.NAME}");put(MediaStore.Images.Media.IS_PENDING,1)}
            // On legacy devices let MediaProvider allocate its public Pictures path and return a writable FD.
            // A just-granted application's old storage namespace may still reject direct mkdir()/File writes.
            // Android 8 MediaProvider.ensureFile/generateFileName performs allocation in the provider namespace.
        }
        val resolver=context.contentResolver;val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Unable to add image to gallery")
        try {
            resolver.openOutputStream(uri,"w")?.use{check(image.compress(Bitmap.CompressFormat.PNG,100,it)){"Unable to encode gallery image"}} ?: error("Unable to open gallery image")
            if(Build.VERSION.SDK_INT>=29)resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            return uri
        }catch(error:Exception){resolver.delete(uri,null,null);throw error}
    }
}
