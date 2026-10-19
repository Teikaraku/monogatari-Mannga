package app.monogatari.android.backup

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Windows renameTo cannot overwrite an existing file; Android's POSIX rename can. */
@Implements(AtomicFile::class, minSdk = 29)
class PosixAtomicFileShadow {
    @RealObject lateinit var real: AtomicFile
    @Implementation fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync(); stream.close()
        Files.move(File(real.baseFile.path + ".new").toPath(), real.baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
