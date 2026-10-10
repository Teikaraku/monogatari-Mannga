package app.monogatari.android.dictionary

import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.objects
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class VocabularySource(val manga: Manga?, val chapter: Chapter?) {
    companion object {
        suspend fun resolve(repo: AppRepository, entry: VocabularyEntry): VocabularySource {
            val split = entry.mangaId.split("::", limit = 2)
            if (split.size != 2 || split.any(String::isBlank)) return VocabularySource(null, null)
            fun cached(): VocabularySource {
                val state = repo.state.value; val manga = state.manga.firstOrNull { it.id == entry.mangaId }
                val chapter = manga?.chapters?.firstOrNull { it.key == entry.chapterKey }
                    ?: JSONObject(state.records).optJSONArray("history").objects().firstOrNull { it.optString("sourceId") == split[0] && it.optString("mangaId") == split[1] && it.optString("chapterId") == entry.chapterKey }
                        ?.optJSONObject("_chapter")?.let(Chapter::parse)
                return VocabularySource(manga, chapter)
            }
            var result = cached()
            val cachedManga = result.manga
            if ((cachedManga == null || result.chapter == null || JSONObject(cachedManga.metadata).optBoolean("_androidMissingDetails")) && repo.state.value.sources.any { it.id == split[0] }) {
                try { repo.refresh(result.manga ?: Manga(split[1], split[0], split[1])); result = cached() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Retain offline cache, exactly as the original details view does. */ }
            }
            return result
        }
    }
}
