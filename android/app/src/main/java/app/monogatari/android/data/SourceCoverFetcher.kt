package app.monogatari.android.data

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException

/**
 * A cover that has to come through its source (request headers, the CDN alias, connection recovery).
 * Coil keeps it in its memory and disk caches like any other image, so a cover downloads once instead of on every appearance.
 */
data class SourceCover(val sourceId: String, val url: String) {
    val cacheKey get() = "source-cover:$sourceId:$url"
}

class SourceCoverKeyer : Keyer<SourceCover> {
    override fun key(data: SourceCover, options: Options) = data.cacheKey
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class SourceCoverFetcher(private val data: SourceCover, private val options: Options, private val loader: ImageLoader) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = loader.diskCache
        cache?.openSnapshot(data.cacheKey)?.let { snapshot ->
            return SourceResult(ImageSource(snapshot.data, cache.fileSystem, data.cacheKey, snapshot), null, DataSource.DISK)
        }
        val repo = AppRepository.get(options.context)
        val source = repo.state.value.sources.firstOrNull { it.id == data.sourceId } ?: throw IOException("Source ${data.sourceId} is not installed")
        val bytes = withContext(Dispatchers.IO) { repo.client.coverBytes(source, data.url) }
        val editor = cache?.openEditor(data.cacheKey)
        if (cache != null && editor != null) {
            try {
                cache.fileSystem.write(editor.data) { write(bytes) }
                editor.commitAndOpenSnapshot()?.let { snapshot ->
                    return SourceResult(ImageSource(snapshot.data, cache.fileSystem, data.cacheKey, snapshot), null, DataSource.NETWORK)
                }
            } catch (error: Exception) {
                runCatching { editor.abort() }
            }
        }
        return SourceResult(ImageSource(Buffer().write(bytes), options.context), null, DataSource.NETWORK)
    }

    class Factory : Fetcher.Factory<SourceCover> {
        override fun create(data: SourceCover, options: Options, imageLoader: ImageLoader) = SourceCoverFetcher(data, options, imageLoader)
    }
}
