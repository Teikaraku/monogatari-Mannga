import Foundation

actor CopyContentRoute {
    private var preferred: URL?
    func current(fallback base: URL) -> URL { preferred ?? base }
    func succeeded(_ base: URL) { preferred = base }
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
