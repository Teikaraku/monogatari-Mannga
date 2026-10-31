//
//  AccountSync.swift
//  Monogatari
//
//  Runs sync rounds for the signed-in 物语 account: at launch, entering the background and coming back,
//  a few seconds after local changes (library, history, categories, settings) and on demand. Rounds
//  never overlap. Mirrors android/.../account/AccountSync.kt.
//

import AuthenticationServices
import Combine
import CoreData
import UIKit

@MainActor
final class AccountSync: ObservableObject {
    static let shared = AccountSync()

    enum Phase { case idle, syncing, failed }

    @Published private(set) var phase: Phase = .idle
    /// quota_exceeded, network, unauthorized, device_revoked or server
    @Published private(set) var error: String?
    @Published private(set) var lastSynced: Date?
    @Published private(set) var user: AccountUser?
    /// A one-line confirmation for the account pages to show (welcome, password changed, ...).
    @Published var notice: String?

    let account = AccountService.shared
    let api = SyncApi()
    let myAccount = MyAccountApi()
    private let ledger = DefaultsSyncLedger()
    private var running: Task<SyncReport?, Never>?
    private var again = false
    private var debounce: Task<Void, Never>?
    private var observers: [AnyCancellable] = []
    /// Set while a round writes, so its own notifications do not schedule another round.
    private var applying = false

    private init() {
        lastSynced = ledger.lastSynced
        account.user.receive(on: RunLoop.main).sink { [weak self] user in
            guard let self else { return }
            self.user = user
            if user == nil { self.debounce?.cancel(); self.phase = .idle; self.error = nil }
        }.store(in: &observers)
    }

    /// Called once at launch.
    func start() {
        let center = NotificationCenter.default
        let local: [Notification.Name] = [.updateLibrary, .addToLibrary, .removeFromLibrary, .updateHistory, .historyAdded,
                                          .historyRemoved, .historySet, .updateCategories, .updateMangaCategories]
            + syncedSettings.map { Notification.Name($0) }
        for name in local {
            center.publisher(for: name).receive(on: RunLoop.main).sink { [weak self] _ in
                guard let self, !self.applying else { return }
                self.schedule()
            }.store(in: &observers)
        }
        center.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in self?.backgroundRound() }.store(in: &observers)
        center.publisher(for: UIApplication.willEnterForegroundNotification).sink { [weak self] _ in self?.schedule(after: 0.5) }.store(in: &observers)
        schedule(after: 0)
    }

    /// Syncs a few seconds after the last local change, so a burst of edits is one round.
    func schedule(after delay: TimeInterval = 8) {
        guard user != nil else { return }
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.syncNow()
        }
    }

    /// Entering the background: the system grants a short while to send what is pending.
    private func backgroundRound() {
        guard user != nil else { return }
        var task: UIBackgroundTaskIdentifier = .invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "account-sync") { UIApplication.shared.endBackgroundTask(task) }
        Task {
            await syncNow()
            UIApplication.shared.endBackgroundTask(task)
        }
    }

    /// Runs a round now, or right after the one in progress.
    @discardableResult
    func syncNow() async -> SyncReport? {
        debounce?.cancel()
        guard let user else { return nil }
        if let running {
            again = true
            return await running.value
        }
        let task = Task { await run(for: user) }
        running = task
        let report = await task.value
        running = nil
        if again { again = false; schedule(after: 0) }
        return report
    }

    private func run(for user: AccountUser) async -> SyncReport? {
        phase = .syncing
        error = nil
        var changedSettings: [(String, Any?)] = []
        let container = CoreDataManager.shared.container
        do {
            let report = try await withRound(container: container, account: user.id) { key, value in changedSettings.append((key, value)) }
            let now = Date()
            ledger.lastSynced = now
            lastSynced = now
            phase = .idle
            if report.applied > 0 {
                applying = true
                for (key, value) in changedSettings { NotificationCenter.default.post(name: .init(key), object: value) }
                NotificationCenter.default.post(name: .updateCategories, object: nil)
                NotificationCenter.default.post(name: .updateLibrary, object: nil)
                NotificationCenter.default.post(name: .updateHistory, object: nil)
                // Let the notifications drain before local changes count again.
                DispatchQueue.main.async { self.applying = false }
            }
            return report
        } catch let failure as SyncApiError {
            if failure.code == "device_revoked" {
                // Signed out from another device's list. Local data stays; signing in again works.
                await account.signOut()
            }
            phase = .failed
            error = failure.code
        } catch let failure as AccountError {
            phase = .failed
            error = failure.code == "signed_out" ? "unauthorized" : "network"
        } catch {
            LogManager.logger.error("Account sync: \(error)")
            phase = .failed
            self.error = "server"
        }
        return nil
    }

    /// One round: every adapter works in the same background context, saved when each step ends.
    private func withRound(container: NSPersistentContainer, account: String,
                           changed: @escaping (String, Any?) -> Void) async throws -> SyncReport {
        let context = container.newBackgroundContext()
        context.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        // Collected on the context's queue, handed over once the round is done.
        let settings = SettingChanges()
        defer { settings.values.forEach { changed($0.0, $0.1) } }
        let adapters: [SyncAdapter] = [
            CategoryAdapter(context: context), LibraryAdapter(context: context), ProgressAdapter(context: context),
            PrefAdapter { key, value in settings.append((key, value)) }
        ]
        let engine = SyncEngine(ledger: ledger, remote: api, account: account, adapters: adapters, transaction: { body in
            await context.perform {
                body()
                if context.hasChanges {
                    do { try context.save() } catch { LogManager.logger.error("Account sync save: \(error)"); context.rollback() }
                }
            }
        })
        return try await engine.sync()
    }

    private final class SettingChanges: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [(String, Any?)] = []
        func append(_ item: (String, Any?)) { lock.lock(); items.append(item); lock.unlock() }
        var values: [(String, Any?)] { lock.lock(); defer { lock.unlock() }; return items }
    }

    func completeSignIn(code: String, verifier: String) async throws {
        try await account.completeSignIn(code: code, verifier: verifier)
        await syncNow()
    }

    func signInWithBrowser(_ provider: String, anchor: ASPresentationAnchor) async throws {
        _ = try await account.signInWithBrowser(provider, anchor: anchor)
        await syncNow()
    }

    /// Signs out here, sending what is pending first; local data stays.
    func signOut() async {
        if user != nil {
            // Wait up to 15 s for the last round; signing out never hangs on a slow network.
            let round = Task { await syncNow() }
            let deadline = Task { try? await Task.sleep(nanoseconds: 15_000_000_000); round.cancel() }
            _ = await round.value
            deadline.cancel()
        }
        debounce?.cancel()
        await account.signOut()
    }

    /// Deletes this app's data from the account (records and device list) and signs out here.
    /// The 物语 account itself, used by the website and the other apps, is kept.
    func deleteAppData() async throws {
        try await api.erase()
        // Forget what was agreed, so this device does not push it all back on the next sign-in.
        ledger.clear()
        await account.signOut()
    }
}
