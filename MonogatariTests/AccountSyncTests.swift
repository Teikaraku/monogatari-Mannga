//
//  AccountSyncTests.swift
//  Monogatari
//
//  The sync engine between two devices and an in-memory server, plus the sign-in helpers.
//

import CryptoKit
import Foundation
import Testing

@testable import Monogatari

/// The sync service in memory: newest write wins (ties: deleted, then payload), sequence, paging, reset.
final class FakeSyncServer: @unchecked Sendable {
    private var rows: [String: (seq: Int64, record: SyncRecord)] = [:]
    private(set) var seq: Int64 = 0
    private(set) var pushes = 0
    var pageSize = 3

    private func wins(_ new: SyncRecord, _ old: SyncRecord) -> Bool {
        if new.updated != old.updated { return new.updated > old.updated }
        if new.deleted != old.deleted { return new.deleted }
        return contentHash(new.data) > contentHash(old.data)
    }

    func record(_ kind: String, _ key: String) -> SyncRecord? { rows[kind + "\n" + key]?.record }
    func erase() { rows = [:]; seq = 0 }
    var kinds: Set<String> { Set(rows.values.map(\.record.kind)) }

    struct Remote: SyncRemote {
        let server: FakeSyncServer
        func pull(since: Int64) async throws -> SyncPage {
            let after = server.rows.values.filter { $0.seq > since }.sorted { $0.seq < $1.seq }
            let page = Array(after.prefix(server.pageSize))
            let reset = since > server.seq
            let cursor = page.last?.seq ?? (reset ? server.seq : since)
            // Through JSON, as on the network.
            let items = page.map { SyncRecord(json: try! JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: $0.record.json)) as! [String: Any])! }
            return SyncPage(items: items, cursor: cursor, more: page.count == server.pageSize && cursor < server.seq, reset: reset, seq: server.seq)
        }
        func push(_ records: [SyncRecord]) async throws -> Int64? {
            server.pushes += 1
            for record in records {
                let id = record.kind + "\n" + record.key
                if let old = server.rows[id]?.record, !server.wins(record, old) { continue }
                server.seq += 1
                server.rows[id] = (server.seq, record)
            }
            return server.seq
        }
    }
}

final class MemoryLedger: SyncLedger {
    var owner: String?
    var cursor: Int64 = 0
    var lastSynced: Date?
    private var agreements: [String: [String: Agreed]] = [:]
    private var waiting: [String: SyncRecord] = [:]
    func agreed(_ kind: String) -> [String: Agreed] { agreements[kind] ?? [:] }
    func setAgreed(_ kind: String, _ key: String, _ value: Agreed?) { agreements[kind, default: [:]][key] = value }
    var parked: [SyncRecord] { Array(waiting.values) }
    func park(_ record: SyncRecord) { waiting[record.kind + "\n" + record.key] = record }
    func unpark(_ kind: String, _ key: String) { waiting[kind + "\n" + key] = nil }
    func clear() { agreements = [:]; waiting = [:]; cursor = 0; owner = nil }
}

/// A device's library and progress, in memory, behind the same adapter protocol the Core Data ones use.
final class MemoryLibrary: @unchecked Sendable {
    var library: [String: [String: Any]] = [:]        // key -> data
    var progress: [String: [String: Any]] = [:]
    var titlesHere: Set<String> = []                    // "source\nmanga" present on this device

    struct Library: SyncAdapter {
        let store: MemoryLibrary
        let kind = "library"
        func scan() -> [LocalItem] { store.library.map { LocalItem(key: $0.key, data: $0.value, updated: nil) } }
        func apply(_ record: SyncRecord) -> Bool {
            store.library[record.key] = record.data
            if let source = record.data?["source"] as? String, let manga = record.data?["manga"] as? String { store.titlesHere.insert(source + "\n" + manga) }
            return true
        }
        func remove(_ key: String) { store.library[key] = nil }
    }

    struct Progress: SyncAdapter {
        let store: MemoryLibrary
        let kind = "progress"
        let dependsOn = ["library"]
        func scan() -> [LocalItem] {
            store.progress.map { LocalItem(key: $0.key, data: $0.value, updated: ($0.value["read"] as? NSNumber)?.int64Value) }
        }
        func apply(_ record: SyncRecord) -> Bool {
            guard let source = record.data?["source"] as? String, let manga = record.data?["manga"] as? String,
                  store.titlesHere.contains(source + "\n" + manga) else { return false }
            store.progress[record.key] = record.data
            return true
        }
        func remove(_ key: String) { store.progress[key] = nil }
    }
}

final class Device {
    let server: FakeSyncServer
    var account: String
    let ledger = MemoryLedger()
    let store = MemoryLibrary()
    var clock: Int64 = 1_000_000

    init(_ server: FakeSyncServer, account: String = "alice") { self.server = server; self.account = account }

    func sync() async throws -> SyncReport {
        try await SyncEngine(ledger: ledger, remote: FakeSyncServer.Remote(server: server), account: account,
                             adapters: [MemoryLibrary.Library(store: store), MemoryLibrary.Progress(store: store)],
                             transaction: { body in body() }, now: { [clock] in clock }).sync()
    }

    func add(_ manga: String, categories: [String] = []) {
        store.library[syncKey("copy2026", manga)] = ["source": "copy2026", "manga": manga, "title": manga, "cover": "", "categories": categories, "added": 500]
        store.titlesHere.insert("copy2026\n" + manga)
    }

    func read(_ manga: String, _ chapter: String, page: Int, at: Int64) {
        store.titlesHere.insert("copy2026\n" + manga)
        store.progress[syncKey("copy2026", manga, chapter)] = ["source": "copy2026", "manga": manga, "chapter": chapter, "page": page,
                                                              "total": 20, "completed": false, "read": at]
    }

    func page(_ manga: String, _ chapter: String) -> Int? { (store.progress[syncKey("copy2026", manga, chapter)]?["page"] as? NSNumber)?.intValue }
}

struct AccountSyncTests {
    @Test func libraryAndProgressReachTheOtherDevice() async throws {
        let server = FakeSyncServer()
        let phone = Device(server)
        phone.add("one-piece", categories: ["在读"])
        phone.read("one-piece", "c1", page: 7, at: 2_000_000)
        _ = try await phone.sync()

        let tablet = Device(server)
        _ = try await tablet.sync()
        #expect(tablet.store.library[syncKey("copy2026", "one-piece")]?["categories"] as? [String] == ["在读"])
        #expect(tablet.page("one-piece", "c1") == 7)
        #expect(server.kinds == ["library", "progress"])
    }

    @Test func theNewerReadWinsAndDevicesConverge() async throws {
        let server = FakeSyncServer()
        let phone = Device(server), tablet = Device(server)
        phone.add("m"); tablet.add("m")
        _ = try await phone.sync(); _ = try await tablet.sync()

        phone.read("m", "c1", page: 3, at: 2_000_000); phone.clock = 2_000_001
        tablet.read("m", "c1", page: 12, at: 3_000_000); tablet.clock = 3_000_001
        _ = try await phone.sync(); _ = try await tablet.sync(); _ = try await phone.sync()
        #expect(phone.page("m", "c1") == 12)
        #expect(tablet.page("m", "c1") == 12)
        let pushes = server.pushes
        _ = try await phone.sync(); _ = try await tablet.sync()
        #expect(server.pushes == pushes)
    }

    @Test func aLaterLocalEditIsNotOverwrittenByAnOlderPull() async throws {
        let server = FakeSyncServer()
        let phone = Device(server), tablet = Device(server)
        phone.add("m"); tablet.add("m")
        phone.read("m", "c1", page: 2, at: 2_000_000)
        _ = try await phone.sync()
        tablet.read("m", "c1", page: 9, at: 4_000_000)
        _ = try await tablet.sync()
        #expect(tablet.page("m", "c1") == 9)
        #expect((server.record("progress", syncKey("copy2026", "m", "c1"))?.data?["page"] as? NSNumber)?.intValue == 9)
    }

    @Test func removalPropagatesAndAnotherAccountStartsOver() async throws {
        let server = FakeSyncServer()
        let phone = Device(server)
        phone.add("a"); phone.add("b")
        _ = try await phone.sync()
        let tablet = Device(server)
        _ = try await tablet.sync()
        #expect(tablet.store.library.count == 2)

        phone.clock += 10_000
        phone.store.library[syncKey("copy2026", "a")] = nil
        _ = try await phone.sync(); _ = try await tablet.sync()
        #expect(tablet.store.library[syncKey("copy2026", "a")] == nil)
        #expect(server.record("library", syncKey("copy2026", "a"))?.deleted == true)

        // Bob on the same phone: nothing of Alice's is deleted in his account.
        phone.account = "bob"
        phone.store.library = [:]
        let before = server.record("library", syncKey("copy2026", "b"))
        _ = try await phone.sync()
        #expect(server.record("library", syncKey("copy2026", "b")) == before)
        #expect(phone.ledger.owner == "bob")
    }

    @Test func progressWaitsForItsTitleAndAnErasedAccountStartsOver() async throws {
        let server = FakeSyncServer()
        server.pageSize = 2
        let phone = Device(server)
        phone.read("history-only", "c1", page: 4, at: 2_000_000)
        for index in 1...5 { phone.add("m\(index)") }
        _ = try await phone.sync()
        #expect(phone.ledger.cursor == server.seq)

        let tablet = Device(server)
        _ = try await tablet.sync()
        #expect(tablet.ledger.parked.count == 1)
        tablet.store.titlesHere.insert("copy2026\nhistory-only")
        _ = try await tablet.sync()
        #expect(tablet.page("history-only", "c1") == 4)
        #expect(tablet.ledger.parked.isEmpty)

        server.erase()
        _ = try await phone.sync()
        #expect(server.record("library", syncKey("copy2026", "m1")) != nil)
    }

    @Test func hashesAndKeysAreStable() {
        #expect(contentHash(["a": 1, "b": ["y": 2.0, "x": true]]) == contentHash(["b": ["x": true, "y": 2], "a": 1.0]))
        #expect(contentHash(["a": 1]) != contentHash(["a": 1.5]))
        // The same key Android computes: hex SHA-256 of the parts joined by newlines.
        #expect(syncKey("copy2026", "m", "c1") == SHA256.hash(data: Data("copy2026\nm\nc1".utf8)).map { String(format: "%02x", $0) }.joined())
    }

    @Test func proofOfWorkAndPkceMatchTheServer() throws {
        let nonce = try solveProofOfWork(salt: "salt-abc", bits: 12)
        #expect(leadingZeroBits(Array(SHA256.hash(data: Data("salt-abc\(nonce)".utf8))), 12))
        #expect(leadingZeroBits([0, 0x0F], 12))
        #expect(!leadingZeroBits([0, 0x10], 12))
        let verifier = PKCE.random(48)
        #expect(verifier.count == 64)
        #expect(PKCE.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let url = PKCE.authorizeUrl(state: "s", challenge: "c", extra: ["direct_sign_in": "social:github-universal"])
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        #expect(items.first { $0.name == "resource" }?.value == AccountConfig.resource)
        #expect(items.first { $0.name == "scope" }?.value == "openid profile email offline_access")
        #expect(items.first { $0.name == "direct_sign_in" }?.value == "social:github-universal")
        #expect(throws: AuthFailure(code: "invalid_state")) { try PKCE.authorizationCode(URL(string: "app.monogatari.monogatari://callback?code=x&state=other")!, state: "s") }
        #expect(try PKCE.authorizationCode(URL(string: "app.monogatari.monogatari://callback?code=x&state=s")!, state: "s") == "x")
    }

    @Test func passwordRulesMatchTheIdentityCenter() {
        #expect(passwordProblem("short1") == "MO_PASSWORD_TOO_SHORT")
        #expect(passwordProblem("onlyletters") == "MO_PASSWORD_TYPES")
        #expect(passwordProblem("letters123") == nil)
        #expect(passwordProblem(String(repeating: "a1", count: 37)) == "MO_PASSWORD_TOO_LONG")
        #expect(passwordStrength("") == 0 && passwordStrength("abcdefg1") == 2 && passwordStrength("abcdefg1!") == 3)
    }
}
