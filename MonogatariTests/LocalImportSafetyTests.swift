@testable import Monogatari
import Foundation
import Testing
import ZIPFoundation

struct LocalImportSafetyTests {
    @Test func rejectsTraversalAndInvalidNames() {
        for name in [".", "..", "../Downloads", "A/B", "A\\B", "A\u{0}B", ""] {
            #expect(!LocalFileManager.isValidFileName(name))
        }
        #expect(LocalFileManager.isValidFileName("作品 01"))
    }

    @Test func legacyPathsCannotDeleteOutsideLocalFolder() {
        let documents = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        #expect(LocalFileManager.localItemURL(filePath: "Local/../Downloads", documentsDirectory: documents) == nil)
        #expect(LocalFileManager.localItemURL(filePath: "Local/..", documentsDirectory: documents) == nil)
        #expect(LocalFileManager.localItemURL(filePath: "Local", documentsDirectory: documents) == nil)
        #expect(LocalFileManager.localItemURL(filePath: "Local/Series/chapter.cbz", documentsDirectory: documents) != nil)
    }

    @Test func imageArchiveProducesPreview() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("cbz")
        defer { try? FileManager.default.removeItem(at: path) }
        let archive = try Archive(url: path, accessMode: .create)
        let image = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="))
        try archive.addEntry(with: "pages/001.png", type: .file, uncompressedSize: Int64(image.count)) { position, size in
            image.subdata(in: Int(position)..<Int(position) + size)
        }
        let info = try #require(await LocalFileManager.shared.loadImportFileInfo(url: path))
        #expect(info.pageCount == 1)
        #expect(info.previewImages.count == 1)
    }
}
