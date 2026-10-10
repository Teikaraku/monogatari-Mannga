@testable import Monogatari
import Foundation
import Testing

@MainActor
struct DownloadCacheResetTests {
    @Test func clearingDownloadsAlsoClearsLoadedCache() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { root.removeItem() }
        let cache = DownloadCache(directoryURL: root)
        let chapter = ChapterIdentifier(sourceKey: "test", mangaKey: "series", chapterKey: "1")
        cache.directory(for: chapter).createDirectory()
        #expect(cache.isChapterDownloaded(identifier: chapter))

        cache.removeAll()
        #expect(!cache.isChapterDownloaded(identifier: chapter))
        #expect(!cache.hasDownloadedChapter(from: chapter.mangaIdentifier))

        cache.directory(for: chapter).createDirectory()
        cache.add(chapter: chapter)
        #expect(cache.isChapterDownloaded(identifier: chapter))
    }
}
