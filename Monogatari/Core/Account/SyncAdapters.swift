//
//  SyncAdapters.swift
//  Monogatari
//
//  The library, reading progress, categories and settings as sync records, read from and written to
//  Core Data in one background context per round. Shapes match android/.../account/SyncAdapters.kt.
//

import CoreData
import Foundation

private let localSource = "local"
private func millis(_ date: Date?) -> Int64 { Int64((date ?? .distantPast).timeIntervalSince1970 * 1000) }
private func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

/// Titles on the bookshelf and their categories.
struct LibraryAdapter: SyncAdapter {
    let context: NSManagedObjectContext
    let kind = "library"
    let dependsOn = ["category"]

    func scan() -> [LocalItem] {
        CoreDataManager.shared.getLibraryManga(context: context).compactMap { library in
            guard let manga = library.manga, manga.sourceId != localSource else { return nil }
            let categories = ((library.categories as? Set<CategoryObject>) ?? []).filter { !$0.group }.compactMap(\.title).sorted()
            return LocalItem(key: syncKey(manga.sourceId, manga.id), data: [
                "source": manga.sourceId, "manga": manga.id, "title": manga.title, "cover": manga.cover ?? "",
                "categories": categories, "added": millis(library.dateAdded)
            ], updated: nil)
        }
    }

    func apply(_ record: SyncRecord) -> Bool {
        guard let data = record.data, let source = data["source"] as? String, let key = data["manga"] as? String,
              source != localSource, syncKey(source, key) == record.key else { return true }
        let identifier = MangaIdentifier(sourceKey: source, mangaKey: key)
        let manga = CoreDataManager.shared.getManga(mangaId: identifier, context: context) ?? {
            // Shown from the record until the source is refreshed, which fills in chapters and details.
            let object = MangaObject(context: context)
            object.sourceId = source; object.id = key
            object.title = (data["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? key
            object.cover = (data["cover"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return object
        }()
        let library = manga.libraryObject ?? {
            let object = LibraryMangaObject(context: context)
            object.manga = manga
            return object
        }()
        if let added = (data["added"] as? NSNumber)?.int64Value, added > 0 { library.dateAdded = date(added) }
        let titles = data["categories"] as? [String] ?? []
        for title in titles where !CoreDataManager.shared.hasCategory(title: title, context: context) {
            CoreDataManager.shared.createCategory(title: title, context: context)
        }
        // Filter groups stay on the device; only plain categories follow the record.
        let groups = ((library.categories as? Set<CategoryObject>) ?? []).filter(\.group)
        let plain = titles.compactMap { CoreDataManager.shared.getCategory(title: $0, context: context) }.filter { !$0.group }
        library.categories = NSSet(set: groups.union(plain))
        return true
    }

    func remove(_ key: String) {
        let ids = CoreDataManager.shared.getLibraryManga(context: context).compactMap(\.manga)
            .filter { syncKey($0.sourceId, $0.id) == key }.map(\.identifier)
        CoreDataManager.shared.removeFromLibrary(ids: ids, context: context)
    }
}

/// Reading progress per chapter. iOS keeps progress 1-based in History; records carry the 0-based page.
struct ProgressAdapter: SyncAdapter {
    let context: NSManagedObjectContext
    let kind = "progress"
    let dependsOn = ["library"]

    func scan() -> [LocalItem] {
        CoreDataManager.shared.getHistory(context: context).compactMap { history in
            guard history.sourceId != localSource, !history.sourceId.isEmpty else { return nil }
            let read = history.dateRead.flatMap { $0 > .distantPast ? millis($0) : nil } ?? 0
            guard read > 0 || history.completed else { return nil }
            var data: [String: Any] = [
                "source": history.sourceId, "manga": history.mangaId, "chapter": history.chapterId,
                "page": max(Int(history.progress) - 1, 0), "total": Int(history.total), "completed": history.completed, "read": read
            ]
            if let position = history.scrollPosition?.doubleValue, position.isFinite { data["position"] = position }
            return LocalItem(key: syncKey(history.sourceId, history.mangaId, history.chapterId), data: data, updated: read)
        }
    }

    func apply(_ record: SyncRecord) -> Bool {
        guard let data = record.data, let source = data["source"] as? String, let manga = data["manga"] as? String,
              let chapter = data["chapter"] as? String, source != localSource, syncKey(source, manga, chapter) == record.key else { return true }
        // The title is not on this device yet: wait for its library record or for it to be opened.
        guard CoreDataManager.shared.hasManga(mangaId: MangaIdentifier(sourceKey: source, mangaKey: manga), context: context) else { return false }
        let page = (data["page"] as? NSNumber)?.intValue ?? 0
        let total = (data["total"] as? NSNumber)?.intValue ?? 0
        let read = (data["read"] as? NSNumber)?.int64Value ?? record.updated
        let history = CoreDataManager.shared.getOrCreateHistory(
            chapterId: ChapterIdentifier(sourceKey: source, mangaKey: manga, chapterKey: chapter), context: context)
        history.progress = Int16(clamping: total > 0 ? min(page, total - 1) + 1 : page + 1)
        history.total = Int16(clamping: total)
        history.completed = data["completed"] as? Bool ?? false
        history.dateRead = read > 0 ? date(read) : .distantPast
        history.scrollPosition = (data["position"] as? NSNumber).map { NSNumber(value: min(max($0.doubleValue, 0), 1)) }
        if let library = CoreDataManager.shared.getLibraryManga(mangaId: MangaIdentifier(sourceKey: source, mangaKey: manga), context: context),
           read > 0, (library.lastRead ?? .distantPast) < date(read) {
            library.lastRead = date(read)
        }
        return true
    }

    func remove(_ key: String) {
        for history in CoreDataManager.shared.getHistory(context: context)
        where syncKey(history.sourceId, history.mangaId, history.chapterId) == key {
            context.delete(history)
        }
    }
}

/// Library categories and their order. Filter groups stay on the device.
struct CategoryAdapter: SyncAdapter {
    let context: NSManagedObjectContext
    let kind = "category"

    private var plain: [CategoryObject] { CoreDataManager.shared.getCategories(sorted: true, context: context).filter { !$0.group } }

    func scan() -> [LocalItem] {
        plain.enumerated().compactMap { index, category in
            guard let title = category.title else { return nil }
            return LocalItem(key: syncKey(title), data: ["title": title, "sort": index], updated: nil)
        }
    }

    func apply(_ record: SyncRecord) -> Bool {
        guard let title = record.data?["title"] as? String, !title.trimmingCharacters(in: .whitespaces).isEmpty,
              syncKey(title) == record.key else { return true }
        if !CoreDataManager.shared.hasCategory(title: title, context: context) {
            CoreDataManager.shared.createCategory(title: title, context: context)
        }
        let sort = (record.data?["sort"] as? NSNumber)?.intValue ?? 0
        var ordered = plain.filter { $0.title != title }
        if let object = CoreDataManager.shared.getCategory(title: title, context: context) {
            ordered.insert(object, at: min(max(sort, 0), ordered.count))
        }
        for (index, category) in ordered.enumerated() where category.sort != index { category.sort = Int16(index) }
        return true
    }

    func remove(_ key: String) {
        guard let title = plain.first(where: { $0.title.map { syncKey($0) } == key })?.title else { return }
        CoreDataManager.shared.removeCategory(title: title, context: context)
    }
}

/// Settings that follow the account, by the names iOS and Android share. Credentials, tokens, locks,
/// network and download behaviour, notifications, performance options and internal state stay here.
let syncedSettings: Set<String> = [
    "General.appearance", "General.useSystemAppearance", "Appearance.layout",
    "Library.opensReaderView", "Library.resumeLastOpenedChapter", "Library.continueReadingOnReselect",
    "Library.unreadChapterBadges", "Library.downloadedChapterBadges", "Library.pinTitles", "Library.showUncategorizedCategory",
    "Library.sortOption", "Library.sortAscending", "Library.listView", "Library.skipTitles", "Library.refreshMetadata",
    "Reader.readingMode", "Reader.skipDuplicateChapters", "Reader.markDuplicateChapters", "Reader.cropBorders",
    "Reader.disableQuickActions", "Reader.disableDoubleTap", "Reader.liveText", "Reader.hideBarsOnSwipe", "Reader.backgroundColor",
    "Reader.tapZones", "Reader.invertTapZones", "Reader.animatePageTransitions", "Reader.pagesToPreload", "Reader.pagedPageLayout",
    "Reader.pagedPageOffset", "Reader.splitWideImages", "Reader.reverseSplitOrder", "Reader.verticalInfiniteScroll",
    "Reader.autoScrollSpeed", "Reader.autoScrollPosition", "Reader.pillarbox", "Reader.pillarboxAmount", "Reader.pillarboxOrientation",
    "Reader.textReaderStyle", "Reader.textFontSize", "Reader.textLineSpacing", "Reader.textHorizontalPadding", "Reader.textTheme",
    "Reader.textAppearance", "Tracking.updateAfterReading", "Tracking.autoSyncFromTracker"
]

/// One record per synced setting the user has set (a value in the app's own domain, not a registered default).
struct PrefAdapter: SyncAdapter {
    let kind = "pref"
    var defaults = UserDefaults.standard
    /// Settings applied this round; posted once the round is saved, so open screens update.
    let changed: (String, Any?) -> Void

    private var stored: [String: Any] {
        defaults.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "") ?? [:]
    }

    func scan() -> [LocalItem] {
        let values = stored
        return syncedSettings.sorted().compactMap { key in
            guard let value = values[key], value is String || value is NSNumber || value is [Any] else { return nil }
            return LocalItem(key: key, data: ["value": value], updated: nil)
        }
    }

    func apply(_ record: SyncRecord) -> Bool {
        guard syncedSettings.contains(record.key), let value = record.data?["value"],
              value is String || value is NSNumber || value is [Any] else { return true }
        defaults.set(value, forKey: record.key)
        changed(record.key, value)
        return true
    }

    func remove(_ key: String) {
        guard syncedSettings.contains(key) else { return }
        defaults.removeObject(forKey: key)
        changed(key, nil)
    }
}
