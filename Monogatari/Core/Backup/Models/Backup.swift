//
//  Backup.swift
//  Monogatari
//
//  Created by Skitty on 2/26/22.
//

import Foundation

struct Backup: Codable, Hashable, Identifiable, Sendable {
    var id: Int { hashValue }

    var library: [BackupLibraryManga]?
    var history: [BackupHistory]?
    var manga: [BackupManga]?
    var chapters: [BackupChapter]?
    var trackItems: [BackupTrackItem]?
    var readingSessions: [BackupReadingSession]?
    var vocabulary: [BackupVocabEntry]?
    var updates: [BackupUpdate]?
    var categories: [BackupCategory]?
    var sources: [BackupSource]?
    var sourceLists: [String]?
    var settings: [String: JSONAnyValue]?
    var date: Date
    var name: String?
    var automatic: Bool?
    var version: String?

    // Validate fixed-width Core Data fields before restore starts deleting current objects.
    var hasValidStorageValues: Bool {
        func fits16(_ value: Int) -> Bool { Int16(exactly: value) != nil }
        guard (manga ?? []).allSatisfy({
            fits16($0.status) && fits16($0.nsfw) && fits16($0.viewer)
                && fits16($0.chapterFlags ?? 0) && Int32(exactly: $0.editedKeys ?? 0) != nil
        }), (chapters ?? []).allSatisfy({
            fits16($0.sourceOrder) && ($0.chapter?.isFinite ?? true) && ($0.volume?.isFinite ?? true)
        }), (history ?? []).allSatisfy({
            fits16($0.progress ?? -1) && fits16($0.total ?? 0)
        }), (trackItems ?? []).allSatisfy({ fits16($0.chapterOffset ?? 0) }),
            (readingSessions ?? []).allSatisfy({ fits16($0.pagesRead) }),
            (categories ?? []).allSatisfy({ fits16($0.sort ?? 0) }),
            (vocabulary ?? []).allSatisfy({ fits16($0.page) && fits16($0.clozeOffset ?? 0) })
        else { return false }
        return true
    }

    static func load(from url: URL) -> Backup? {
        guard let json = try? Data(contentsOf: url) else { return nil }

        if let backup = try? PropertyListDecoder().decode(Backup.self, from: json) {
            return backup
        } else {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .secondsSince1970
            return try? decoder.decode(Backup.self, from: json)
        }
    }
}
