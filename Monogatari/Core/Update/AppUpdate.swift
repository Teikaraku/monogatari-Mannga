//
//  AppUpdate.swift
//  Monogatari
//
//  Checks the project's update feed. iOS can't install a package from inside the app, so a newer
//  build is downloaded to the phone and handed to the share sheet, to be saved to Files or opened
//  in the signing tool the user installs with. Mirrors android/.../update/AppUpdate.kt.
//

import Combine
import CryptoKit
import SwiftUI
import UIKit

/// One platform's entry in latest.json. A build below `minBuild` must update before the app can be used.
struct AppRelease: Sendable, Equatable {
    let version: String
    let build: Int
    let minBuild: Int
    let urls: [URL]
    let size: Int64
    let sha256: String
    let notes: String

    func forced(current: Int) -> Bool { current < minBuild }

    init?(_ json: [String: Any]) {
        let build = (json["build"] as? NSNumber)?.intValue ?? 0
        let addresses = [json["url"] as? String] + ((json["mirrors"] as? [Any]) ?? []).map { $0 as? String }
        var urls: [URL] = []
        for address in addresses.compactMap({ $0 }) {
            guard let url = URL(string: address), url.scheme == "https", !urls.contains(url) else { continue }
            urls.append(url)
        }
        guard build > 0, !urls.isEmpty else { return nil }
        self.version = json["version"] as? String ?? ""
        self.build = build
        self.minBuild = (json["minBuild"] as? NSNumber)?.intValue ?? 0
        self.urls = urls
        self.size = (json["size"] as? NSNumber)?.int64Value ?? 0
        self.sha256 = (json["sha256"] as? String ?? "").lowercased()
        self.notes = json["notes"] as? String ?? ""
    }
}

struct AppUpdateError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

enum AppUpdateFeed {
    /// The project's own server first; GitHub serves the same file from its newest release.
    static let addresses = [
        URL(string: "https://manga.monogatari.top/app-update/latest.json")!,
        URL(string: "https://github.com/Teikaraku/monogatari-reader/releases/latest/download/latest.json")!
    ]

    /// The newest release for `platform`, or nil when the feed lists none.
    static func fetch(platform: String = "ios", addresses: [URL] = addresses, session: URLSession = .shared) async throws -> AppRelease? {
        var failures: [String] = []
        for address in addresses {
            do {
                var request = URLRequest(url: address, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
                request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                let (data, response) = try await session.data(for: request)
                guard let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else {
                    throw AppUpdateError(message: "HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
                }
                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw AppUpdateError(message: "latest.json")
                }
                return (json[platform] as? [String: Any]).flatMap(AppRelease.init)
            } catch {
                try Task.checkCancellation()
                failures.append("\(address.host ?? ""): \(error.localizedDescription)")
            }
        }
        throw AppUpdateError(message: failures.joined(separator: "\n"))
    }
}

@MainActor
final class AppUpdateManager: ObservableObject {
    static let shared = AppUpdateManager()
    static let skippedBuildKey = "Update.skippedBuild"

    enum Outcome { case available, current, failed }

    @Published private(set) var release: AppRelease?
    @Published private(set) var forced = false
    @Published private(set) var progress: Double?
    @Published private(set) var downloaded: URL?
    @Published private(set) var failure: String?
    private var checked = false

    var currentBuild: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0 }

    /// Runs once per launch on its own; a check from the About page also reports when the app is current.
    @discardableResult
    func check(manual: Bool = false) async -> Outcome {
        if !manual && checked { return release == nil ? .current : .available }
        checked = true
        let latest: AppRelease?
        do {
            latest = try await AppUpdateFeed.fetch()
        } catch {
            return .failed
        }
        guard let latest, latest.build > currentBuild else { return .current }
        let mustUpdate = latest.forced(current: currentBuild)
        if !manual && !mustUpdate && UserDefaults.standard.integer(forKey: Self.skippedBuildKey) == latest.build { return .current }
        if release?.build != latest.build {
            downloaded = nil
            failure = nil
        }
        forced = mustUpdate
        release = latest
        return .available
    }

    func later() {
        guard !forced, progress == nil else { return }
        release = nil
    }

    func skip() {
        guard let release, !forced, progress == nil else { return }
        UserDefaults.standard.set(release.build, forKey: Self.skippedBuildKey)
        self.release = nil
    }

    func download() {
        guard let release, progress == nil else { return }
        progress = 0
        failure = nil
        Task {
            do {
                downloaded = try await fetchPackage(release)
            } catch {
                failure = error.localizedDescription
            }
            progress = nil
        }
    }

    private func fetchPackage(_ release: AppRelease) async throws -> URL {
        let manager = FileManager.default
        let directory = manager.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("updates", isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("Monogatari-\(release.version).ipa")
        for item in (try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] where item != file {
            try? manager.removeItem(at: item)
        }
        if manager.fileExists(atPath: file.path), Self.verified(file, release) { return file }
        var failures: [String] = []
        for url in release.urls {
            do {
                let result = try await UpdateDownload(destination: file) { value in
                    Task { @MainActor in self.progress = value }
                }.run(url)
                guard Self.verified(result, release) else { throw AppUpdateError(message: "安装包校验失败") }
                return result
            } catch {
                try? manager.removeItem(at: file)
                if Task.isCancelled { throw error }
                failures.append("\(url.host ?? ""): \(error.localizedDescription)")
            }
        }
        throw AppUpdateError(message: (["下载失败，请稍后重试。"] + failures).joined(separator: "\n"))
    }

    nonisolated static func verified(_ file: URL, _ release: AppRelease) -> Bool {
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? -1
        if release.size > 0 && size != release.size { return false }
        guard !release.sha256.isEmpty else { return true }
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined() == release.sha256
    }
}

/// One package download with progress, moved to `destination` as soon as it finishes.
private final class UpdateDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let onProgress: @Sendable (Double) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<URL, Error>?

    init(destination: URL, onProgress: @escaping @Sendable (Double) -> Void) {
        self.destination = destination
        self.onProgress = onProgress
    }

    func run(_ url: URL) async throws -> URL {
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                self.continuation = continuation
                lock.unlock()
                session.downloadTask(with: url).resume()
            }
        } onCancel: {
            session.invalidateAndCancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        onProgress(min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            finish(.failure(AppUpdateError(message: "HTTP \(status)")))
            return
        }
        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
            finish(.success(destination))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
    }

    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

/// A required update fills the screen and can't be closed; an optional one waits until the next launch or skips this version.
struct AppUpdateView: View {
    @ObservedObject var manager: AppUpdateManager
    let share: (URL) -> Void

    var body: some View {
        if let release = manager.release {
            VStack(alignment: .leading, spacing: 14) {
                Text(manager.forced ? "需要更新" : "发现新版本").font(.title2.bold())
                Text(details(release)).font(.subheadline).foregroundColor(.secondary)
                if manager.forced {
                    Text("当前版本需要更新后才能继续使用。")
                }
                if !release.notes.isEmpty {
                    ScrollView {
                        Text(release.notes).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 280)
                }
                if let progress = manager.progress {
                    ProgressView(value: progress).tint(bookstorePink)
                    Text("\(Int(progress * 100))%").font(.caption).foregroundColor(.secondary)
                }
                if let failure = manager.failure {
                    Text(failure).font(.footnote).foregroundColor(.red)
                }
                Spacer(minLength: 0)
                if let file = manager.downloaded {
                    Text("安装包已下载，可存储到“文件”或用签名工具打开。").font(.footnote).foregroundColor(.secondary)
                    button("存储或打开安装包") { share(file) }
                } else {
                    button(manager.progress == nil ? "下载新版" : "正在下载") { manager.download() }
                        .disabled(manager.progress != nil)
                }
                if !manager.forced {
                    HStack {
                        Button("跳过此版本") { manager.skip() }
                        Spacer()
                        Button("稍后") { manager.later() }
                    }
                    .foregroundColor(bookstorePink)
                    .disabled(manager.progress != nil)
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
        }
    }

    private func details(_ release: AppRelease) -> String {
        var parts = [release.version]
        if release.size > 0 { parts.append(String(format: "%.1f MB", Double(release.size) / 1_048_576)) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func button(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.headline).frame(maxWidth: .infinity).padding(.vertical, 12)
        }
        .foregroundColor(.white)
        .background(bookstorePink, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Shows the update screen while the manager holds a release, and closes it when the release is put off.
@MainActor
enum AppUpdatePresenter {
    private static weak var shown: UIViewController?
    private static var observation: AnyCancellable?

    static func start() {
        guard observation == nil else { return }
        observation = AppUpdateManager.shared.$release
            .receive(on: RunLoop.main)
            .sink { release in
                if release == nil {
                    shown?.dismiss(animated: true)
                    shown = nil
                } else {
                    present()
                }
            }
    }

    static func present() {
        let manager = AppUpdateManager.shared
        guard shown == nil, manager.release != nil, let top = UIApplication.shared.appDelegate?.topViewController else { return }
        let controller = UIHostingController(rootView: AnyView(EmptyView()))
        controller.rootView = AnyView(AppUpdateView(manager: manager) { [weak controller] file in
            guard let controller else { return }
            let activity = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = controller.view
            activity.popoverPresentationController?.sourceRect = CGRect(x: controller.view.bounds.midX, y: controller.view.bounds.maxY - 80, width: 1, height: 1)
            controller.present(activity, animated: true)
        })
        controller.modalPresentationStyle = manager.forced ? .fullScreen : .formSheet
        // Only the buttons close it, so a required update can't be swiped away.
        controller.isModalInPresentation = true
        top.present(controller, animated: true)
        shown = controller
    }
}
