package app.monogatari.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

class MonogatariApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components {
            add(SvgDecoder.Factory()); if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(app.monogatari.android.data.SourceCoverKeyer()); add(app.monogatari.android.data.SourceCoverFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(100 * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image-cache")).maxSizeBytes(500L * 1024 * 1024).build() }
        // Credentials are added per request and must never follow redirects to a different host.
        .okHttpClient { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }.build()
}
