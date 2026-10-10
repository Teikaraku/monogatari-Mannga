//
//  DownloadTask.swift
//  Monogatari
//
//  Created by Skitty on 5/14/22.
//

import AidokuRunner
import Foundation
import Nuke
import UniformTypeIdentifiers
import ZIPFoundation

protocol DownloadTaskDelegate: AnyObject, Sendable {
    func taskCancelled(task: DownloadTask) async
    func taskPaused(task: DownloadTask) async
    func taskFinished(task: DownloadTask) async
    func downloadProgressChanged(download: Download) async
    func downloadFinished(download: Download) async
    func downloadFailed(download: Download) async
    func downloadCancelled(download: Download) async
}

// performs the actual download operations
actor DownloadTask: Identifiable {
    let id: String

    private let cache: DownloadCache
    private let copyImageClient = CopyClient()
    private var downloads: [Download]
    private weak var delegate: DownloadTaskDelegate?

    private var currentPage: Int = 0
    private var failedPages: Int = 0
    private var failedPageNumbers: [Int] = []
    private var pages: [Page] = []

    private(set) var running: Bool = false
    private var activeTask: Task<Void, Never>?
    private var stopping = false
    private var resumeRequested = false

    private static let maxConcurrentPageTasks = 5

    private static let maxPageRetries = 3
    private static let retryBaseDelay: TimeInterval = 1
    private static let maxRetryDelay: TimeInterval = 60

    enum DownloadError: Error {
        case pageProcessorFailed
    }

    init(id: String, cache: DownloadCache, downloads: [Download]) {
        self.id = id
        self.cache = cache
        self.downloads = downloads
    }

    func setDelegate(delegate: DownloadTaskDelegate?) {
        self.delegate = delegate
    }

    func resume() {
        if stopping {
            resumeRequested = true
            return
        }
        guard !running else { return }
        running = true
        activeTask = Task {
            await next()
        }
    }

    func pause() async {
        stopping = true
        running = false
        for (i, download) in downloads.enumerated() where download.status == .queued || download.status == .downloading {
            downloads[i].status = .paused
        }
        activeTask?.cancel()
        await activeTask?.value
        activeTask = nil
        currentPage = 0
        failedPages = 0
        failedPageNumbers = []
        stopping = false
        await delegate?.taskPaused(task: self)
        if resumeRequested {
            resumeRequested = false
            resume()
        }
    }

    func cancel(manga: MangaIdentifier? = nil, chapter: ChapterIdentifier? = nil) async {
        let cancelled = downloads.filter { download in
            if let chapter { return download.chapterIdentifier == chapter }
            if let manga { return download.mangaIdentifier == manga }
            return true
        }
        guard !cancelled.isEmpty else { return }
        let wasRunning = running
        let cancelledCurrent = downloads.first.map { cancelled.contains($0) } ?? false
        stopping = true
        running = false
        // Wait for all image writes to stop before removing their directories. Preserve the active
        // chapter's completed pages if only a different queued chapter is being cancelled.
        for i in downloads.indices where downloads[i].status == .downloading {
            downloads[i].status = .paused
        }
        activeTask?.cancel()
        await activeTask?.value
        activeTask = nil
        downloads.removeAll { cancelled.contains($0) }
        if cancelledCurrent {
            pages = []
            currentPage = 0
            failedPages = 0
            failedPageNumbers = []
        }
        for var download in cancelled {
            download.status = .cancelled
            cache.tmpDirectory(for: download.chapterIdentifier).removeItem()
            await delegate?.downloadCancelled(download: download)
        }
        stopping = false
        let shouldResume = wasRunning || resumeRequested
        resumeRequested = false
        if downloads.isEmpty {
            await delegate?.taskCancelled(task: self)
        } else if shouldResume {
            resume()
        }
    }

    func add(download: Download) {
        guard !downloads.contains(where: { $0 == download }) else { return }
        downloads.append(download)
    }
}

extension DownloadTask {
    private func next() async {
        guard running && !Task.isCancelled else { return }

        // done with all downloads
        if downloads.isEmpty {
            running = false
            await delegate?.taskFinished(task: self)
            return
        }

        // attempt to download first chapter in the queue
        if
            let download = downloads.first,
            let source = await SourceManager.shared.source(for: download.chapterIdentifier.sourceKey)
        {
            guard running && !Task.isCancelled && downloads.first == download else { return }
            // if chapter already downloaded, skip
            let directory = cache.directory(for: download.chapterIdentifier)
            guard !directory.exists && !directory.appendingPathExtension("cbz").exists else {
                downloads.removeFirst()
                await delegate?.downloadFinished(download: download)
                return await next()
            }

            // download has been cancelled or failed, skip
            if download.status != .queued && download.status != .downloading && download.status != .paused {
                downloads.removeFirst()
                await delegate?.downloadCancelled(download: download)
                return await next()
            }

            await self.download(from: source)
        } else {
            guard running && !Task.isCancelled && !downloads.isEmpty else { return }
            // source not found, skip this download
            downloads.removeFirst()
            await next()
        }
    }

    struct NetworkPage {
        let url: URL
        let context: PageContext?
        let targetPath: URL
        /// The page's position in the chapter, one-based, which is the number it is stored under.
        let pageNumber: Int
    }

    /// A finished page download, carrying the page it belongs to.
    ///
    /// A concurrent download hands results back in whatever order they finish, so a failure has to
    /// name its page rather than be inferred from how many have completed.
    struct PageDownloadResult {
        let page: NetworkPage
        let data: Data?
        let targetPath: URL?
    }

    // perform download
    private func download(from source: AidokuRunner.Source) async {
        guard running && !Task.isCancelled && !downloads.isEmpty else { return }

        let download = downloads[0]
        let resuming = download.status == .paused
        downloads[0].status = .downloading

        let tmpDirectory = cache.tmpDirectory(for: download.chapterIdentifier)
        if !resuming {
            tmpDirectory.removeItem() // remove in case it exists from a previous failed download
        }
        tmpDirectory.createDirectory()
        currentPage = 0
        failedPages = 0
        failedPageNumbers = []

        if pages.isEmpty {
            let language = download.chapter.language ?? source.languages.first
            let fetchedPages = ((try? await source.getPageList(
                manga: download.manga,
                chapter: download.chapter
            )) ?? []).map {
                $0.toOld(
                    sourceId: source.key,
                    chapterId: download.chapterIdentifier.chapterKey,
                    language: language
                )
            }
            guard running && !Task.isCancelled && downloads.first == download else { return }
            pages = fetchedPages
            downloads[0].total = pages.count
        }

        var networkPages: [NetworkPage] = []

        for (i, page) in pages.enumerated() {
            guard running && !Task.isCancelled && downloads.first == download else { return }
            let pageNumber = String(format: "%03d", i + 1)
            let targetPath = tmpDirectory.appendingPathComponent(pageNumber)

            if let urlString = page.imageURL, let url = URL(string: urlString) {
                let alreadySaved = resuming && tmpDirectory.contents.contains {
                    $0.deletingPathExtension().lastPathComponent == pageNumber
                        && LocalFileManager.allowedImageExtensions.contains($0.pathExtension.lowercased())
                }
                if alreadySaved {
                    currentPage += 1
                    continue
                }
                // add pages that require network requests to a concurrent queue
                networkPages.append(.init(
                    url: url,
                    context: page.context,
                    targetPath: targetPath,
                    pageNumber: i + 1
                ))
            } else {
                currentPage += 1
                do {
                    if let base64 = page.base64, let data = Data(base64Encoded: base64) {
                        try data.write(to: targetPath.appendingPathExtension("png"))
                    } else if let text = page.text, let data = text.data(using: .utf8) {
                        try data.write(to: targetPath.appendingPathExtension("txt"))
                    } else if let image = page.image {
                        let data = image.pngData()
                        try data?.write(to: targetPath.appendingPathExtension("png"))
                    }
                } catch {
                    failedPages += 1
                    failedPageNumbers.append(i + 1)
                    LogManager.logger.error("Error writing page data: \(error)")
                }
            }

            if page.hasDescription {
                var description = page.description
                if description == nil {
                    description = try? await source.getPageDescription(page: page.toNew())
                }
                guard running && !Task.isCancelled && downloads.first == download else { return }
                if let description {
                    let data = description.data(using: .utf8)
                    try? data?.write(to: targetPath.appendingPathExtension("desc.txt"))
                }
            }
        }

        if AppSettings.downloads.parallel.get() {
            // download pages from the network concurrently
            await withTaskGroup(of: PageDownloadResult.self) { taskGroup in
                let groupSize = max(1, min(source.config?.maximumParallelRequests ?? Self.maxConcurrentPageTasks, Self.maxConcurrentPageTasks))
                for pageGroup in networkPages.chunked(into: groupSize) {
                    for page in pageGroup {
                        taskGroup.addTask {
                            await self.downloadPage(page, source: source, tmpDirectory: tmpDirectory)
                        }
                    }
                    for await result in taskGroup {
                        guard running && !Task.isCancelled && tmpDirectory.exists else {
                            // download was cancelled, stop processing
                            taskGroup.cancelAll()
                            return
                        }
                        var writeFailed = false
                        if let data = result.data, let path = result.targetPath {
                            do {
                                try data.write(to: path)
                            } catch {
                                writeFailed = true
                                LogManager.logger.error("Error writing downloaded image: \(error)")
                            }
                        }
                        await self.incrementProgress(
                            for: download.chapterIdentifier,
                            failedPage: result.data == nil || writeFailed ? result.page.pageNumber : nil
                        )
                    }
                }
            }
        } else {
            // download pages from the network serially
            for page in networkPages {
                let result = await self.downloadPage(page, source: source, tmpDirectory: tmpDirectory)
                guard running && !Task.isCancelled && tmpDirectory.exists else {
                    // download was cancelled, stop processing
                    return
                }
                var writeFailed = false
                if let data = result.data, let path = result.targetPath {
                    do {
                        try data.write(to: path)
                    } catch {
                        writeFailed = true
                        LogManager.logger.error("Error writing downloaded image: \(error)")
                    }
                }
                await self.incrementProgress(
                    for: download.chapterIdentifier,
                    failedPage: result.data == nil || writeFailed ? page.pageNumber : nil
                )
            }
        }

        // handle completion of the current download
        if running && !Task.isCancelled && networkPages.isEmpty && currentPage == pages.count {
            await handleChapterDownloadFinish(download: download)
        }
    }

    // fetch a single page's data
    private func downloadPage(
        _ page: NetworkPage,
        source: AidokuRunner.Source,
        tmpDirectory: URL
    ) async -> PageDownloadResult {
        let urlRequest = await source.getModifiedImageRequest(
            url: page.url,
            context: page.context
        )

        let result = await self.fetchPageData(for: urlRequest, tmpDirectory: tmpDirectory)

        var resultData: Data?
        var resultPath: URL?

        if source.features.processesPages {
            let image = result.flatMap { PlatformImage(data: $0.0) } ?? .mangaPlaceholder
            do {
                let pageInterceptor = PageInterceptorProcessor(source: source, pageContext: page.context)
                let container = ImageContainer(image: image, data: result?.0)
                let request = ImageRequest(
                    urlRequest: urlRequest,
                    userInfo: [.processesKey: true]
                )
                let newImage = try await pageInterceptor.processAsync(
                    container,
                    context: .init(
                        request: request,
                        response: .init(
                            container: container,
                            request: request,
                            urlResponse: result?.1 ?? (request.url ?? request.urlRequest?.url).flatMap {
                                HTTPURLResponse(
                                    url: $0,
                                    statusCode: 404,
                                    httpVersion: nil,
                                    headerFields: nil
                                )
                            }
                        ),
                        isCompleted: true
                    )
                )
                guard let newImage else {
                    throw DownloadError.pageProcessorFailed
                }
                resultData = newImage.pngData()
                resultPath = page.targetPath.appendingPathExtension("png")
            } catch {
                LogManager.logger.error("Error processing image: \(error)")
            }
        } else if let (data, res) = result {
            let fileExtention = self.guessFileExtension(response: res, defaultValue: "png")
            resultData = data
            resultPath = page.targetPath.appendingPathExtension(fileExtention)
        }

        return .init(page: page, data: resultData, targetPath: resultPath)
    }

    // fetch page data, retrying on any non-2xx HTTP response or network error
    private func fetchPageData(for urlRequest: URLRequest, tmpDirectory: URL) async -> (Data, URLResponse)? {
        var attempt = 0
        while true {
            guard running && !Task.isCancelled else { return nil }
            let result = if CopyClient.isCopyImage(urlRequest.url) {
                try? await copyImageClient.imageData(for: urlRequest)
            } else {
                try? await URLSession.shared.data(for: urlRequest)
            }
            guard running && !Task.isCancelled else { return nil }

            // response was okay, bail out
            if let result, self.isSuccessResponse(result.1) {
                return result
            }

            let statusCode = (result?.1 as? HTTPURLResponse)?.statusCode

            let reason = switch statusCode {
                case 429: "rate limited (HTTP 429)"
                case let statusCode?: "HTTP \(statusCode)"
                case nil: "network error"
            }

            let isRetryable = statusCode.map(self.isRetryableStatus) ?? true

            guard isRetryable, attempt < Self.maxPageRetries else {
                LogManager.logger.error("Failed to download image (\(reason)): \(urlRequest)")
                return nil
            }

            // honor the server's Retry-After when present, otherwise use exponential backoff
            let (delay, waitSource): (TimeInterval, String) = if let retryAfter = self.retryDelay(from: result?.1) {
                (retryAfter, "Retry-After")
            } else {
                (Self.backoffDelay(forAttempt: attempt), "backoff")
            }
            LogManager.logger.warn("Image download \(reason), retrying in \(Int(delay))s via \(waitSource): \(urlRequest)")

            // stop retrying if the download was cancelled
            guard tmpDirectory.exists else { return nil }
            do {
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            } catch {
                return nil // task cancelled during backoff
            }
            guard tmpDirectory.exists else { return nil }

            attempt += 1
        }
    }

    private func incrementProgress(for id: ChapterIdentifier, failedPage: Int? = nil) async {
        guard running && !Task.isCancelled else { return }
        guard let downloadIndex = downloads.firstIndex(where: { $0.chapterIdentifier == id }) else {
            return
        }
        currentPage += 1
        downloads[downloadIndex].progress = currentPage
        let download = downloads[downloadIndex]
        Task {
            await delegate?.downloadProgressChanged(download: download)
        }
        if let failedPage {
            failedPages += 1
            failedPageNumbers.append(failedPage)
        }
        if currentPage == pages.count {
            await handleChapterDownloadFinish(download: download)
        }
    }

    private func handleChapterDownloadFinish(download: Download) async {
        guard running && !Task.isCancelled && downloads.first == download else { return }
        let tmpDirectory = cache.tmpDirectory(for: download.chapterIdentifier)

        if failedPages == pages.count {
            // the entire chapter failed to download, skip adding to cache and cancel
            tmpDirectory.removeItem()
            if let downloadIndex = downloads.firstIndex(where: { $0 == download }) {
                downloads[downloadIndex].status = .cancelled
                downloads.remove(at: downloadIndex)
                await delegate?.downloadCancelled(download: download)
            }
            LogManager.logger.error("Chapter failed to download: \(download.chapter.formattedTitle())")
        } else if failedPages > 0 {
            LogManager.logger.error(
                "Chapter downloaded with \(failedPages) failed page\(failedPages == 1 ? "" : "s"): \(download.chapter.formattedTitle())"
            )

            // save metadata for failed download in downloads view
            await DownloadManager.shared.saveChapterMetadata(manga: download.manga, chapter: download.chapter, to: tmpDirectory)
            guard running && !Task.isCancelled && downloads.first == download else { return }

            markFailed(tmpDirectory: tmpDirectory)

            if let downloadIndex = downloads.firstIndex(where: { $0 == download }) {
                downloads[downloadIndex].status = .failed
                let failed = downloads.remove(at: downloadIndex)
                await delegate?.downloadFailed(download: failed)
            }
        } else {
            do {
                // Save chapter metadata after successful download
                await DownloadManager.shared.saveChapterMetadata(manga: download.manga, chapter: download.chapter, to: tmpDirectory)
                guard running && !Task.isCancelled && downloads.first == download else { return }

                let directory = cache.directory(for: download.chapterIdentifier)

                try FileManager.default.moveItem(at: tmpDirectory, to: directory)

                if AppSettings.downloads.compress.get() {
                    try FileManager.default.zipItem(at: directory, to: directory.appendingPathExtension("cbz"), shouldKeepParent: false)
                    directory.removeItem()
                }

                // save manga cover if not already present
                let mangaDirectory = cache.directory(for: download.mangaIdentifier)
                let coverPath = mangaDirectory.appendingPathComponent("cover.png")
                if
                    !coverPath.exists,
                    let coverUrl = download.manga.cover.flatMap({ URL(string: $0) }),
                    let source = await SourceManager.shared.source(for: download.chapterIdentifier.sourceKey)
                {
                    let request = await source.getModifiedImageRequest(url: coverUrl, context: nil)
                    let result = if CopyClient.isCopyImage(request.url) {
                        try? await copyImageClient.imageData(for: request)
                    } else {
                        try? await URLSession.shared.data(for: request)
                    }
                    if let data = result?.0, self.isSuccessResponse(result?.1) {
                        try? data.write(to: coverPath)
                    }
                }

                await cache.add(chapter: download.chapterIdentifier)
            } catch {
                guard !Task.isCancelled else { return }
                LogManager.logger.error("Error moving temporary download directory (\(tmpDirectory)) to final location: \(error)")
                if let downloadIndex = downloads.firstIndex(where: { $0 == download }) {
                    downloads[downloadIndex].status = .failed
                    let failed = downloads.remove(at: downloadIndex)
                    await delegate?.downloadFailed(download: failed)
                }
                pages = []
                currentPage = 0
                failedPages = 0
                failedPageNumbers = []
                return await next()
            }
            guard !Task.isCancelled else { return }
            if let downloadIndex = downloads.firstIndex(where: { $0 == download }) {
                downloads[downloadIndex].status = .finished
                downloads.remove(at: downloadIndex)
                await delegate?.downloadFinished(download: download)
            }
        }
        guard !Task.isCancelled else { return }
        pages = []
        currentPage = 0
        failedPages = 0
        failedPageNumbers = []
        await next()
    }

    // store file with failed pages in failed download directory
    private func markFailed(tmpDirectory: URL) {
        let marker = cache.failureMarker(inTmpDirectory: tmpDirectory)
        do {
            try JSONEncoder().encode(failedPageNumbers.sorted()).write(to: marker)
        } catch {
            LogManager.logger.error("Error marking failed download (\(tmpDirectory)): \(error)")
        }
    }
}

// MARK: Utility
extension DownloadTask {
    private nonisolated func isSuccessResponse(_ response: URLResponse?) -> Bool {
        guard let httpResponse = response as? HTTPURLResponse else { return true }

        // redirect are followed by URLSession
        return (200..<300).contains(httpResponse.statusCode)
    }

    private nonisolated func isRetryableStatus(_ statusCode: Int) -> Bool {
        statusCode == 429 || (500...599).contains(statusCode)
    }

    // exponential backoff
    private nonisolated static func backoffDelay(forAttempt attempt: Int) -> TimeInterval {
        min(maxRetryDelay, retryBaseDelay * pow(2, Double(attempt)))
    }

    private nonisolated func retryDelay(from response: URLResponse?) -> TimeInterval? {
        guard
            let httpResponse = response as? HTTPURLResponse,
            let value = httpResponse.value(forHTTPHeaderField: "Retry-After")?
                .trimmingCharacters(in: .whitespaces),
            !value.isEmpty
        else {
            return nil
        }

        // Retry-After: <delay-seconds>
        if let seconds = TimeInterval(value) {
            return min(Self.maxRetryDelay, max(0, seconds))
        }

        // Retry-After: <http-date> (e.g. "Wed, 21 Oct 2015 07:28:00 GMT")
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        if let date = formatter.date(from: value) {
            return min(Self.maxRetryDelay, max(0, date.timeIntervalSinceNow))
        }

        return nil
    }

    private nonisolated func guessFileExtension(response: URLResponse, defaultValue: String) -> String {
        if
            let suggestedFilename = response.suggestedFilename,
            !suggestedFilename.isEmpty,
            let pathExtension = URL(string: suggestedFilename)?.pathExtension,
            LocalFileManager.allowedImageExtensions.contains(pathExtension.lowercased())
        {
            return pathExtension
        }
        if
            let mimeType = response.mimeType,
            let type = UTType(mimeType: mimeType),
            let pathExtension = type.preferredFilenameExtension,
            LocalFileManager.allowedImageExtensions.contains(pathExtension)
        {
            return pathExtension
        }
        return defaultValue
    }
}
