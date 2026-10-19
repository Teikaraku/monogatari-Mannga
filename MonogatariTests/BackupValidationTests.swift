import Foundation
import Testing
@testable import Monogatari

struct BackupValidationTests {
    private func decode(_ json: String) throws -> Backup {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return try decoder.decode(Backup.self, from: Data(json.utf8))
    }

    @Test func rejectsOverflowBeforeRestoringSessions() throws {
        let backup = try decode("""
        {"date":0,"readingSessions":[{"pagesRead":32768,"startDate":0,"endDate":1,"sourceId":"s","mangaId":"m","chapterId":"c"}]}
        """)
        #expect(!backup.hasValidStorageValues)
    }

    @Test func acceptsStoredIntegerBoundary() throws {
        let backup = try decode("""
        {"date":0,"readingSessions":[{"pagesRead":32767,"startDate":0,"endDate":1,"sourceId":"s","mangaId":"m","chapterId":"c"}]}
        """)
        #expect(backup.hasValidStorageValues)
    }

    @Test func rejectsHistoryAndMangaOverflow() throws {
        let history = try decode("""
        {"date":0,"history":[{"dateRead":1,"sourceId":"s","chapterId":"c","mangaId":"m","progress":1,"total":32768,"completed":false}]}
        """)
        #expect(!history.hasValidStorageValues)
        let manga = try decode("""
        {"date":0,"manga":[{"id":"m","sourceId":"s","title":"Manga","status":1,"nsfw":0,"viewer":0,"editedKeys":2147483648}]}
        """)
        #expect(!manga.hasValidStorageValues)
    }

    @Test func sourceIdentityIncludesBothComponents() {
        let first = MangaIdentifier(sourceKey: "source-a", mangaKey: "same-key")
        let second = MangaIdentifier(sourceKey: "source-b", mangaKey: "same-key")
        let destinations = [first: "first-destination", second: "second-destination"]
        #expect(destinations.count == 2)
        #expect(destinations[first] == "first-destination")
        #expect(destinations[second] == "second-destination")
    }

    @Test func rejectsVocabularyClozeOffsetOverflow() throws {
        for offset in [32768, -32769] {
            let backup = try decode("""
            {"date":0,"vocabulary":[{"sourceId":"s","mangaId":"m","chapterId":"c","word":"word","page":1,"createdDate":0,"clozeOffset":\(offset)}]}
            """)
            #expect(!backup.hasValidStorageValues)
        }
    }

    @Test func acceptsVocabularyClozeOffsetBoundariesAndMissingOffset() throws {
        for offset in [32767, -32768] {
            let backup = try decode("""
            {"date":0,"vocabulary":[{"sourceId":"s","mangaId":"m","chapterId":"c","word":"word","page":1,"createdDate":0,"clozeOffset":\(offset)}]}
            """)
            #expect(backup.hasValidStorageValues)
        }
        let backup = try decode("""
        {"date":0,"vocabulary":[{"sourceId":"s","mangaId":"m","chapterId":"c","word":"word","page":1,"createdDate":0}]}
        """)
        #expect(backup.hasValidStorageValues)
    }
}
