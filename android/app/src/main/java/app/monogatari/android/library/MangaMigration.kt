package app.monogatari.android.library

import app.monogatari.android.data.*
import app.monogatari.android.reader.ReaderOptionStore
import app.monogatari.android.tracking.TrackingRepository
import app.monogatari.android.backup.DataRestoreJournal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object MangaMigration {
    suspend fun migrate(repo: AppRepository, oldId: String, destination: Manga, copy: Boolean, afterStateCommit: suspend () -> Unit = {}): Manga = libraryMigrationMutex.withLock {
        require(oldId != destination.id) { "Select a different manga" }
        require(repo.state.value.manga.any { it.id == oldId }) { "Original manga was removed" }
        // Fetching can refresh the destination cache. The migration transaction starts after that read succeeds.
        val updated = repo.refresh(destination)
        val original = repo.state.value; val old = original.manga.firstOrNull { it.id == oldId } ?: error("Original manga was removed")
        require(updated.chapters.isNotEmpty() || old.chapters.isEmpty()) { "Destination chapters could not be loaded" }
        val globalChapterIds = original.sources.firstOrNull { it.id == old.sourceId }?.type in setOf("komga", "kavita", "suwayomi")
        var result = migratedManga(old, updated, globalChapterIds)
        val reader = ReaderOptionStore(repo.context); val readerSnapshot = reader.export(); val newReader = JSONObject(readerSnapshot.toString())
        readerSnapshot.opt("series:$oldId")?.let { newReader.put("series:${result.id}", it) }
        if (!copy) newReader.remove("series:$oldId")
        val tracking = TrackingRepository.get(repo.context)
        tracking.migrationTransaction {
        val trackerSnapshot = tracking.data.value.json(); var stateChanged = false
        val journal = DataRestoreJournal.create(repo.context, original, trackerSnapshot, readerSnapshot)
        val affected = setOf(oldId, result.id); journal.put("affectedManga", org.json.JSONArray(affected.toList())); DataRestoreJournal.write(repo.context, journal)
        try {
            val identicalChapters = if (old.sourceId == result.sourceId) old.chapters.filter { a -> result.chapters.any { b -> b.key == a.key && (globalChapterIds || a.url.isNotBlank() && a.url == b.url) } }.associate { it.key to it.key } else emptyMap()
            tracking.migrateBindings(oldId, result.id, copy, identicalChapters)
            tracking.bindEnhancedSources(listOf(result), original.sources)
            restoreReaderOptions(repo.context, newReader)
            repo.change { state ->
                val currentOld = state.manga.firstOrNull { it.id == oldId } ?: error("Original manga was removed")
                val currentNew = state.manga.firstOrNull { it.id == result.id } ?: updated
                result = migratedManga(currentOld, currentNew, globalChapterIds)
                val settings = migratedMangaReaderSettings(JSONObject(state.settings), currentOld, result, copy, knownMangaIds(state))
                // Removing the library entry retains history, sessions, vocabulary references and actual downloads.
                val retainedOld = if (copy) currentOld else currentOld.copy(inLibrary = false, categories = emptyList(), pinned = false)
                state.copy(manga = state.manga.filter { it.id != oldId && it.id != result.id } + retainedOld + result, settings = settings.toString())
            }; stateChanged = true
            afterStateCommit()
            repo.change { state -> state.copy(settings = JSONObject(state.settings).put(DataRestoreJournal.marker, journal.getString("id")).toString()) }
            DataRestoreJournal.remove(repo.context, journal)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                suspend fun attempt(block: suspend () -> Unit) { try { block() } catch (rollback: Throwable) { error.addSuppressed(rollback) } }
                if (stateChanged) attempt { repo.change { current -> DataRestoreJournal.undoState(current, journal) } }
                attempt { tracking.restoreSourceSnapshot(trackerSnapshot, affected) }
                attempt { SourceMigrationJournal.restoreReader(repo.context, readerSnapshot, affected) }
                if (error.suppressed.isEmpty()) DataRestoreJournal.remove(repo.context, journal)
            }; throw error
        }
        result
        }
    }
    internal fun migratedManga(old: Manga, destination: Manga, globalChapterIds: Boolean = false): Manga {
        val completed = old.chapters.filter { it.completed }
        fun number(c: Chapter) = app.monogatari.android.reader.ChapterListing.number(c)
        val maxChapter = completed.mapNotNull(::number).maxOrNull()?.takeUnless { it == -1.0 }
        val threshold = maxChapter ?: completed.mapNotNull { it.volume }.maxOrNull()
        val chapterReads = if (threshold == null) emptySet() else destination.chapters.filter { number(it)?.let { n -> n <= threshold } == true }.map { it.key }.toSet()
        val read = if (chapterReads.isNotEmpty() || threshold == null) chapterReads else destination.chapters.filter { it.volume != null && it.volume <= threshold }.map { it.key }.toSet()
        val completedDate = completed.maxOfOrNull { it.readAt }?.takeIf { it > 0 } ?: System.currentTimeMillis()
        return destination.copy(inLibrary = true, categories = (old.categories + destination.categories).distinct(), pinned = old.pinned || destination.pinned,
            addedAt = minOf(old.addedAt, destination.addedAt), chapters = destination.chapters.map { chapter ->
                // General source chapter keys are manga scoped; only a proven matching URL or built-in global ID can reuse a file.
                val identical = old.chapters.firstOrNull { it.key == chapter.key && old.sourceId == destination.sourceId && (globalChapterIds || it.url.isNotBlank() && it.url == chapter.url) }
                val dates = completed.firstOrNull { it.number == chapter.number && it.volume == chapter.volume }
                val progress = if (chapter.key in read) chapter.copy(completed = true, readAt = maxOf(chapter.readAt, dates?.readAt ?: completedDate)) else chapter
                if (identical?.downloaded == true && !progress.downloaded) progress.copy(pages = identical.pages, downloaded = true, total = maxOf(progress.total, identical.total), text = identical.text, html = identical.html)
                else progress
            })
    }
    private fun migratedMangaReaderSettings(original: JSONObject, old: Manga, destination: Manga, copy: Boolean, knownIds: Set<String>): JSONObject = JSONObject(original.toString()).also { result ->
        val known = knownIds + old.id + destination.id; val next = "${destination.sourceId}.${destination.key}"
        original.keys().asSequence().mapNotNull { key -> mangaPreferenceOwner(key, known)?.takeIf { it.mangaId == old.id }?.let { key to it } }.toList().forEach { (key, owner) -> result.put(key.removeSuffix(owner.suffix) + next, original.get(key)); if (!copy) result.remove(key) }
    }
}
