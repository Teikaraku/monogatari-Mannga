package app.monogatari.android.dictionary

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object VocabularyMedia {
    fun image(context: Context, path: String): File {
        val file = File(if (path.startsWith("file:")) Uri.parse(path).path ?: error("Invalid media URI") else path).canonicalFile
        require(file.isFile && (file.toPath().startsWith(context.filesDir.canonicalFile.toPath()) || file.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))) { "Vocabulary media must be stored inside the application" }
        require(file.length() <= 16L * 1024 * 1024) { "Vocabulary media exceeds the size limit" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Vocabulary media is not a decodable image" }; return file
    }
}
