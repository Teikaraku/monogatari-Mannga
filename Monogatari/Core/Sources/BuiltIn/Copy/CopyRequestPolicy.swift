import Foundation
import AidokuRunner

struct CopyMangaSnapshot: Sendable {
    let manga: AidokuRunner.Manga
    let groups: [(String, String)]
}

/// Details and chapters are requested together by the reader. Share their comic2 request.
actor CopyMetadataCache {
    private struct Entry { let until: Date; let value: CopyMangaSnapshot }
    private var cache: [URL: Entry] = [:]
    private var requests: [URL: Task<CopyMangaSnapshot, Error>] = [:]
    func value(_ url: URL, load: @escaping @Sendable () async throws -> CopyMangaSnapshot) async throws -> CopyMangaSnapshot {
        try Task.checkCancellation()
        if let entry = cache[url], entry.until > Date() { return entry.value }
        if let task = requests[url] { return try await task.value }
        let task = Task { try await load() }
        requests[url] = task
        defer { requests[url] = nil }
        let value = try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
        try Task.checkCancellation()
        cache = cache.filter { $0.value.until > Date() }
        if cache.count >= 16, let key = cache.min(by: { $0.value.until < $1.value.until })?.key { cache[key] = nil }
        cache[url] = Entry(until: Date().addingTimeInterval(10), value: value)
        return value
    }
}

actor CopyContentRoute {
    private var preferred: URL?
    init(preferred: URL? = nil) { self.preferred = preferred }
    func current(fallback base: URL) -> URL { preferred ?? base }
    func succeeded(_ base: URL) { preferred = base }
}

/// Routes that just failed to connect, so other requests try them last for two minutes instead of waiting on them again.
actor CopyRouteHealth {
    private var coolingUntil: [String: Date] = [:]
    private func key(_ url: URL) -> String { "\(url.host ?? ""):\(url.port ?? 443)" }
    func cooling(_ url: URL) -> Bool { (coolingUntil[key(url)] ?? .distantPast) > Date() }
    func failed(_ url: URL) { coolingUntil[key(url)] = Date().addingTimeInterval(120) }
    func succeeded(_ url: URL) { coolingUntil[key(url)] = nil }
}

struct CopySearchEndpoint: Sendable, Equatable { let base: URL; let api: Bool }

actor CopySearchRoutes {
    struct Selection: Sendable { let token: UUID; var endpoint: CopySearchEndpoint? }
    private var selections: [String: Selection] = [:]
    func begin(_ query: String, restart: Bool) -> Selection {
        if restart || selections[query] == nil {
            if selections.count >= 16, let key = selections.keys.first { selections[key] = nil }
            selections[query] = Selection(token: UUID(), endpoint: nil)
        }
        return selections[query]!
    }
    func selected(_ query: String, token: UUID, endpoint: CopySearchEndpoint) throws {
        try Task.checkCancellation()
        if selections[query]?.token == token { selections[query]?.endpoint = endpoint }
    }
}

/// A service's cooldown is shared by all requests made by this client.
actor CopyRequestPolicy {
    private var deadlines: [String: Date] = [:]
    static let maximumAutomaticWait: TimeInterval = 5

    private func origin(_ url: URL) -> String { "\(url.scheme ?? "https")://\(url.host ?? ""):\(url.port ?? 443)" }

    func before(_ url: URL) async throws {
        while true {
            try Task.checkCancellation()
            deadlines = deadlines.filter { $0.value > Date() }
            let deadline = deadlines[origin(url)] ?? .distantPast
            let delay = deadline.timeIntervalSinceNow
            if delay > Self.maximumAutomaticWait { throw CopyError.rateLimited(deadline) }
            if delay <= 0 { return }
            try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    }

    func limited(_ url: URL, retryAfter: String?, serverDate: String?, attempt: Int) -> CopyError {
        let delay = Self.retryDelay(retryAfter, serverDate: serverDate) ?? (2 * pow(2, Double(min(attempt, 2))))
        let deadline = Date().addingTimeInterval(delay)
        deadlines[origin(url)] = max(deadlines[origin(url)] ?? .distantPast, deadline)
        return .rateLimited(deadline)
    }

    nonisolated static func retryDelay(_ header: String?, serverDate: String? = nil, now: Date = Date()) -> TimeInterval? {
        guard let header else { return nil }
        if let seconds = Double(header.trimmingCharacters(in: .whitespaces)), seconds.isFinite, seconds >= 0 {
            return max(1, seconds)
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let deadline = formatter.date(from: header) else { return nil }
        return max(1, deadline.timeIntervalSince(serverDate.flatMap { formatter.date(from: $0) } ?? now))
    }
}

/// Repeated reader/download requests share a short-lived page list and one network operation.
/// The queue also prevents preloading different chapters from producing a burst of requests.
actor CopyReadCache {
    private struct Entry { let until: Date; let pages: [String] }
    private var cache: [URL: Entry] = [:]
    private var requests: [URL: Task<[String], Error>] = [:]
    private var tail: Task<[String], Error>?
    private var lastStarted = Date.distantPast

    func pages(_ url: URL, load: @escaping @Sendable () async throws -> [String]) async throws -> [String] {
        try Task.checkCancellation()
        if let entry = cache[url], entry.until > Date() { return entry.pages }
        if let existing = requests[url] { return try await existing.value }
        let previous = tail
        let task = Task {
            if let previous { _ = try? await previous.value }
            try Task.checkCancellation()
            try await self.spaceRequests()
            return try await load()
        }
        requests[url] = task
        tail = task
        defer { requests[url] = nil }
        let pages = try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
        try Task.checkCancellation()
        cache = cache.filter { $0.value.until > Date() }
        if cache.count >= 32, let oldest = cache.min(by: { $0.value.until < $1.value.until })?.key { cache[oldest] = nil }
        cache[url] = Entry(until: Date().addingTimeInterval(300), pages: pages)
        return pages
    }

    private func spaceRequests() async throws {
        let wait = lastStarted.addingTimeInterval(0.5).timeIntervalSinceNow
        if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        try Task.checkCancellation()
        lastStarted = Date()
    }
}
