//
//  SyncEngine.swift
//  Monogatari
//
//  Two-way, record-level sync of the library with the account, the same records and rules as
//  android/.../account/SyncEngine.kt so both apps share one account's data:
//    library   sha256(source \n manga)            {source, manga, title, cover, categories, added}
//    progress  sha256(source \n manga \n chapter) {source, manga, chapter, page (0-based), total, completed, read, position?}
//    category  sha256(title)                      {title, sort}
//    pref      the setting's name                 {value}
//  Times are milliseconds since the epoch. Local files stay on the device.
//

import CryptoKit
import Foundation

/// A synced item as it is on this device. `updated` is when it last changed here, when the data records it.
struct LocalItem {
    let key: String
    let data: [String: Any]
    let updated: Int64?
}

/// The content and time last agreed with the server for one record.
struct Agreed: Codable, Equatable {
    var hash: String?
    var updated: Int64
}

/// Reads and writes one kind of record. `scan` and `apply` run inside one store transaction per round.
protocol SyncAdapter {
    var kind: String { get }
    var dependsOn: [String] { get }
    func scan() -> [LocalItem]
    /// Applies the record; false when this device cannot hold it yet (its title is not here).
    func apply(_ record: SyncRecord) -> Bool
    func remove(_ key: String)
}

extension SyncAdapter { var dependsOn: [String] { [] } }

/// What this device remembers about its sync with the signed-in account.
protocol SyncLedger: AnyObject {
    var owner: String? { get set }
    var cursor: Int64 { get set }
    var lastSynced: Date? { get set }
    func agreed(_ kind: String) -> [String: Agreed]
    func setAgreed(_ kind: String, _ key: String, _ value: Agreed?)
    var parked: [SyncRecord] { get }
    func park(_ record: SyncRecord)
    func unpark(_ kind: String, _ key: String)
    func clear()
}

protocol SyncRemote {
    func pull(since: Int64) async throws -> SyncPage
    func push(_ records: [SyncRecord]) async throws -> Int64?
}

extension SyncApi: SyncRemote {}

struct SyncReport { var pulled = 0, pushed = 0, applied = 0, waiting = 0 }

/// A stable hash: keys sorted at every level, 1.0 and 1 alike (they are after a JSON round trip).
func contentHash(_ data: [String: Any]?) -> String {
    guard let data else { return "" }
    return Insecure.SHA1.hash(data: Data(canonical(data).utf8)).map { String(format: "%02x", $0) }.joined()
}

private func canonical(_ value: Any?) -> String {
    switch value {
        case nil, is NSNull: return "null"
        case let map as [String: Any]:
            return "{" + map.keys.sorted().map { quote($0) + ":" + canonical(map[$0]) }.joined(separator: ",") + "}"
        case let list as [Any]: return "[" + list.map(canonical).joined(separator: ",") + "]"
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue ? "true" : "false" }
            let double = number.doubleValue
            return double == double.rounded() && abs(double) < 1e15 ? String(Int64(double)) : String(double)
        case let text as String: return quote(text)
        default: return quote("\(value!)")
    }
}

/// A JSON string literal. Hashes are only compared on the device that made them, so the exact escaping
/// only has to be stable here.
private func quote(_ text: String) -> String {
    let data = (try? JSONSerialization.data(withJSONObject: text, options: [.fragmentsAllowed])) ?? Data()
    return String(decoding: data, as: UTF8.self)
}

/// Record keys: the hex SHA-256 of the identifying parts, so any characters a source uses in ids fit.
func syncKey(_ parts: String...) -> String {
    SHA256.hash(data: Data(parts.joined(separator: "\n").utf8)).map { String(format: "%02x", $0) }.joined()
}

final class SyncEngine {
    private let ledger: SyncLedger
    private let remote: SyncRemote
    private let account: String
    private let adapters: [SyncAdapter]
    /// Runs the body as one store transaction (a background context, saved at the end).
    private let transaction: (@escaping () -> Void) async -> Void
    private let now: () -> Int64
    static let pushBatch = 500

    init(ledger: SyncLedger, remote: SyncRemote, account: String, adapters: [SyncAdapter],
         transaction: @escaping (@escaping () -> Void) async -> Void, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.ledger = ledger; self.remote = remote; self.account = account; self.adapters = adapters
        self.transaction = transaction; self.now = now
    }

    func sync() async throws -> SyncReport {
        if ledger.owner != account {
            // Another account's agreements must not push its deletions into this one.
            ledger.clear(); ledger.owner = account
        }
        var cursor = ledger.cursor
        var pulled: [SyncRecord] = []
        while true {
            let page = try await remote.pull(since: cursor)
            if page.reset {
                // The account was erased: start over, after reading what other devices already wrote.
                ledger.clear(); ledger.owner = account
                pulled = []; cursor = 0
                continue
            }
            pulled += page.items
            cursor = page.cursor
            if !page.more { break }
        }
        let applied = await apply(pulled)
        ledger.cursor = cursor

        var changes: [(SyncRecord, () -> Void)] = []
        await transaction { changes = self.localChanges() }
        var pushed = 0
        for start in stride(from: 0, to: changes.count, by: Self.pushBatch) {
            let batch = Array(changes[start..<min(start + Self.pushBatch, changes.count)])
            let seq = try await remote.push(batch.map(\.0))
            batch.forEach { $0.1() }
            pushed += batch.count
            // Skip pulling our own writes back, but only when nothing else was written in between.
            if let seq, seq == cursor + Int64(batch.count) { cursor = seq; ledger.cursor = cursor }
        }
        return SyncReport(pulled: pulled.count, pushed: pushed, applied: applied, waiting: ledger.parked.count)
    }

    private func ordered(_ kinds: [String]) -> [String] {
        var result: [String] = []
        func visit(_ kind: String) {
            guard !result.contains(kind) else { return }
            adapters.first { $0.kind == kind }?.dependsOn.forEach(visit)
            result.append(kind)
        }
        kinds.forEach(visit)
        return result
    }

    private func apply(_ pulled: [SyncRecord]) async -> Int {
        var latest: [String: SyncRecord] = [:]
        var order: [String] = []
        for record in ledger.parked + pulled {
            let id = record.kind + "\n" + record.key
            if latest[id] == nil { order.append(id) }
            if (latest[id]?.updated ?? .min) <= record.updated { latest[id] = record }
        }
        guard !latest.isEmpty else { return 0 }
        let records = order.compactMap { latest[$0] }
        var applied = 0
        await transaction {
            for kind in self.ordered(Array(Set(records.map(\.kind))).sorted()) {
                guard let adapter = self.adapters.first(where: { $0.kind == kind }) else { continue } // From a newer app version.
                let agreed = self.ledger.agreed(kind)
                for record in records where record.kind == kind {
                    let local = adapter.scan().first { $0.key == record.key }
                    switch self.applyOne(adapter, record, local, agreed[record.key]) {
                        case .waiting: self.ledger.park(record)
                        case .applied: self.ledger.unpark(kind, record.key); applied += 1
                        case .kept: self.ledger.unpark(kind, record.key)
                    }
                }
            }
        }
        return applied
    }

    private enum Outcome { case applied, kept, waiting }

    private func applyOne(_ adapter: SyncAdapter, _ record: SyncRecord, _ local: LocalItem?, _ agreed: Agreed?) -> Outcome {
        if let agreed, agreed.hash != nil, agreed.updated >= record.updated { return .kept }
        // Changed here since the last agreement, and later than the server's version: keep ours, it is pushed next.
        // An item never agreed counts as changed only when it carries its own change time (reading progress).
        let editedHere = local.map { local in agreed?.hash == nil ? local.updated != nil : contentHash(local.data) != agreed?.hash } ?? false
        if editedHere, let updated = local?.updated, updated > record.updated { return .kept }
        if record.deleted {
            ledger.setAgreed(adapter.kind, record.key, nil)
            guard local != nil else { return .kept }
            adapter.remove(record.key)
            return .applied
        }
        guard adapter.apply(record) else { return .waiting }
        // Agree on the item as this device scans it, so applying does not look like a local edit.
        let after = adapter.scan().first { $0.key == record.key }
        ledger.setAgreed(adapter.kind, record.key, Agreed(hash: contentHash(after?.data ?? record.data), updated: record.updated))
        return .applied
    }

    private func localChanges() -> [(SyncRecord, () -> Void)] {
        var changes: [(SyncRecord, () -> Void)] = []
        let time = now()
        for adapter in adapters {
            let agreed = ledger.agreed(adapter.kind)
            var present = Set<String>()
            for item in adapter.scan() where present.insert(item.key).inserted {
                let hash = contentHash(item.data)
                let known = agreed[item.key]
                if known?.hash == hash { continue }
                // Must beat whatever was agreed; local change times can be missing or older.
                let updated = max(item.updated ?? time, (known?.updated ?? 0) + 1)
                let kind = adapter.kind, key = item.key
                changes.append((SyncRecord(kind: kind, key: key, updated: updated, data: item.data),
                                { self.ledger.setAgreed(kind, key, Agreed(hash: hash, updated: updated)) }))
            }
            for (key, known) in agreed where !present.contains(key) && known.hash != nil {
                let updated = max(time, known.updated + 1)
                let kind = adapter.kind
                changes.append((SyncRecord(kind: kind, key: key, updated: updated, deleted: true), { self.ledger.setAgreed(kind, key, nil) }))
            }
        }
        return changes
    }
}

/// The ledger in UserDefaults (it is small: a hash and a time per record), under one key per account state.
final class DefaultsSyncLedger: SyncLedger {
    private let defaults: UserDefaults
    private let prefix = "Data.accountSync."
    private var agreements: [String: [String: Agreed]]
    /// Waiting records as JSON (their payloads may hold nulls, which property lists cannot).
    private var parkedRecords: [String: [String: Any]]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        agreements = (defaults.data(forKey: "Data.accountSync.agreed")).flatMap { try? JSONDecoder().decode([String: [String: Agreed]].self, from: $0) } ?? [:]
        parkedRecords = defaults.data(forKey: "Data.accountSync.parked")
            .flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: [String: Any]] } ?? [:]
    }

    private func saveParked() {
        defaults.set(try? JSONSerialization.data(withJSONObject: parkedRecords), forKey: prefix + "parked")
    }

    var owner: String? {
        get { defaults.string(forKey: prefix + "owner") }
        set { defaults.set(newValue, forKey: prefix + "owner") }
    }
    var cursor: Int64 {
        get { (defaults.object(forKey: prefix + "cursor") as? NSNumber)?.int64Value ?? 0 }
        set { defaults.set(NSNumber(value: newValue), forKey: prefix + "cursor") }
    }
    var lastSynced: Date? {
        get { defaults.object(forKey: prefix + "last") as? Date }
        set { defaults.set(newValue, forKey: prefix + "last") }
    }

    func agreed(_ kind: String) -> [String: Agreed] { agreements[kind] ?? [:] }

    func setAgreed(_ kind: String, _ key: String, _ value: Agreed?) {
        agreements[kind, default: [:]][key] = value
        defaults.set(try? JSONEncoder().encode(agreements), forKey: prefix + "agreed")
    }

    var parked: [SyncRecord] { parkedRecords.values.compactMap(SyncRecord.init(json:)) }

    func park(_ record: SyncRecord) {
        parkedRecords[record.kind + "\n" + record.key] = record.json
        saveParked()
    }

    func unpark(_ kind: String, _ key: String) {
        guard parkedRecords.removeValue(forKey: kind + "\n" + key) != nil else { return }
        saveParked()
    }

    func clear() {
        agreements = [:]; parkedRecords = [:]
        for key in ["agreed", "parked", "cursor", "owner", "last"] { defaults.removeObject(forKey: prefix + key) }
    }
}
