import Foundation
import AidokuRunner
import Testing
@testable import Monogatari

private final class CopyFixtureProtocol: URLProtocol, @unchecked Sendable {
    enum Reply { case json(String), failure(URLError), status(Int), response(Int, String, [String: String]) }
    private static let lock = NSLock()
    private static var handlers: [String: (URLRequest) -> Reply] = [:]

    static func register(_ host: String, handler: @escaping (URLRequest) -> Reply) {
        lock.lock(); defer { lock.unlock() }
        handlers[host] = handler
    }

    static func remove(_ host: String) {
        lock.lock(); defer { lock.unlock() }
        handlers.removeValue(forKey: host)
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host?.hasSuffix(".copy-fixture.invalid") == true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let handler = Self.handlers[request.url?.host ?? ""]
        Self.lock.unlock()
        guard let handler else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        let reply = handler(request)
        if case .failure(let error) = reply { client?.urlProtocol(self, didFailWithError: error); return }
        let status: Int
        let text: String
        var headers = ["Content-Type": "application/json"]
        switch reply {
            case .json(let json): status = 200; text = json
            case .status(let code): status = code; text = "{}"
            case .response(let code, let body, let extra): status = code; text = body; headers.merge(extra) { _, new in new }
            case .failure: return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
            headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class CopyFixture: @unchecked Sendable {
    let browse: URL
    let content: URL
    let session: URLSession
    private let lock = NSLock()
    private var recorded: [URLRequest] = []

    init(_ handler: @escaping (URLRequest, Bool) -> CopyFixtureProtocol.Reply) {
        let id = UUID().uuidString.lowercased()
        browse = URL(string: "https://browse-\(id).copy-fixture.invalid")!
        content = URL(string: "https://content-\(id).copy-fixture.invalid")!
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CopyFixtureProtocol.self]
        config.urlCache = nil
        session = URLSession(configuration: config)
        for (host, backup) in [(browse.host!, false), (content.host!, true)] {
            CopyFixtureProtocol.register(host) { [weak self] request in
                self?.record(request)
                return handler(request, backup)
            }
        }
    }

    private func record(_ request: URLRequest) {
        lock.lock(); defer { lock.unlock() }
        recorded.append(request)
    }

    var requests: [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return recorded
    }

    var client: CopyClient { .init(session: session, browseBase: browse, contentBase: content, searchBases: [browse, content]) }

    deinit {
        session.invalidateAndCancel()
        CopyFixtureProtocol.remove(browse.host!)
        CopyFixtureProtocol.remove(content.host!)
    }
}

private final class CopyAttemptCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func next() -> Int {
        lock.lock(); defer { lock.unlock() }
        value += 1
        return value
    }
}

struct CopySourceClientTests {
    private let comic = #"{"name":"Fixture comic","path_word":"fixture-comic","cover":"https://images.example/cover.jpg"}"#

    @Test func imageTlsRecoveryDoesNotInvalidateTheSharedBookstoreSession() async throws {
        let attempts = CopyAttemptCounter()
        let fixture = CopyFixture { request, _ in
            if request.url!.path == "/image.webp" {
                return attempts.next() <= 2 ? .failure(URLError(.secureConnectionFailed)) : .response(200, "image-data", [:])
            }
            return .json("{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}")
        }
        let client = fixture.client
        let (data, _) = try await client.imageData(for: client.imageRequest(fixture.browse.appendingPathComponent("image.webp")))
        #expect(String(decoding: data, as: UTF8.self) == "image-data")
        #expect(try await client.catalog(CopyQuery(), page: 1).entries.count == 1)
        #expect(fixture.requests.count == 4)
    }

    @Test func chineseDataLanguageDoesNotInheritTheDevicesEnglishRequestLanguage() async throws {
        let fixture = CopyFixture { request, _ in
            .json(request.value(forHTTPHeaderField: "Accept-Language") == "zh-Hant,zh;q=0.9" ?
                "{\"code\":200,\"results\":{\"recComics\":{\"list\":[\(comic)]}}}" : #"{"code":200,"results":{"recComics":{"list":[]}}}"#)
        }
        let home = try await fixture.client.home()
        #expect(home.sections.count == 1 && home.notice == nil)
        #expect(fixture.requests.count == 1)
    }

    @Test func verifiedImageAliasPreservesPathQueryAndNormalTlsIdentity() {
        let original = URL(string: "https://sm.mangafunb.fun/m/book/page.webp?size=1500&token=a%2Bb")!
        let request = CopyClient().imageRequest(original)
        #expect(request.url?.absoluteString == "https://sm.mangafuna.xyz/m/book/page.webp?size=1500&token=a%2Bb")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(request.value(forHTTPHeaderField: "version") == nil)
        for address in ["https://images.example/a", "https://sm.mangafunb.fun:8443/a", "https://sm.mangafunb.fun.evil.example/a", "http://sm.mangafunb.fun/a"] {
            #expect(CopyClient.imageUrl(URL(string: address)!).absoluteString == address)
        }
    }

    @Test func detailsAndChapterGroupsShareOneMetadataRequest() async throws {
        let fixture = CopyFixture { request, _ in
            request.url!.path.contains("/comic2/") ? .json("{\"code\":200,\"results\":{\"comic\":\(comic),\"groups\":{\"default\":{}}}}") :
                .json(#"{"code":200,"results":{"list":[{"uuid":"one","index":0,"name":"第1话"}],"total":1}}"#)
        }
        let client = fixture.client
        async let details = client.details(key: "fixture-comic")
        async let chapters = client.chapters(key: "fixture-comic")
        let (manga, list) = try await (details, chapters)
        #expect(manga.key == "fixture-comic" && list.count == 1)
        #expect(fixture.requests.filter { $0.url?.path.contains("/comic2/") == true }.count == 1)
        #expect(fixture.requests.count == 2)
    }

    @Test func searchApiFallbackKeepsTheChosenScopeOnLaterPages() async throws {
        let fixture = CopyFixture { request, backup in
            if !backup { return .failure(URLError(.secureConnectionFailed)) }
            let offset = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "offset" }!.value!
            return .json("{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":31,\"offset\":\(offset),\"limit\":30}}")
        }
        let client = CopyClient(session: fixture.session, browseBase: fixture.content, contentBase: fixture.content,
            searchBases: [fixture.content], searchApiBase: fixture.browse)
        let query = CopyQuery(kind: "search", keyword: "金牌得主")
        #expect(try await client.catalog(query, page: 1).more)
        let second = try await client.catalog(query, page: 2)
        #expect(!second.more)
        #expect(fixture.requests.filter { $0.url?.host == fixture.browse.host }.count == 2)
        #expect(fixture.requests.last?.url?.path == "/api/kb/web/searchcl/comics")
    }

    @Test func readingConnectionFailuresUseTheVerifiedContentRouteAndKeepItForNextChapter() async throws {
        let body = #"{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}"#
        let fixture = CopyFixture { _, backup in backup ? .json(body) : .failure(URLError(.secureConnectionFailed)) }
        let client = CopyClient(session: fixture.session, browseBase: fixture.content, contentBase: fixture.browse,
            contentAlternates: [fixture.content])
        for chapter in ["188", "187", "188"] {
            #expect(try await client.pages(key: "manga", chapter: chapter) == ["https://images.example/page.webp"])
        }
        #expect(fixture.requests.filter { $0.url?.host == fixture.browse.host }.count == 2)
        #expect(fixture.requests.filter { $0.url?.host == fixture.content.host }.count == 2)
        let request = try #require(fixture.requests.last)
        #expect(request.value(forHTTPHeaderField: "version") == "2024.4.28")
        #expect(request.value(forHTTPHeaderField: "dnts") == "3")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "platform" && $0.value == "3" } == true)
    }

    private func fixtureBody(_ name: String) throws -> String {
        let bundle = Bundle(for: CopyFixtureProtocol.self)
        let address = try #require(bundle.url(forResource: name, withExtension: "json") ??
            bundle.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try String(contentsOf: address, encoding: .utf8)
    }

    @Test func actualHomePayloadsRetainBannersRecommendationsAndTheCorrectRankPeriod() throws {
        for name in ["primary", "alternate"] {
            let value = try JSONSerialization.jsonObject(with: Data(fixtureBody("copy-home-\(name)").utf8)) as! [String: Any]
            let home = try CopyClient().parseHome(value["results"] as! [String: Any])
            #expect(home.features.count == 5)
            #expect(home.sections.contains { $0.id == "recComics" })
            #expect(!home.topics.isEmpty && !home.rankings.isEmpty && home.notice == nil)
            if name == "primary" { #expect(home.sections.count == 5) }
            else {
                #expect(home.sections.first { $0.id == "hotComics" }?.title == "本周更新")
                #expect(home.rankings.first?.period == "week")
            }
        }
    }

    @Test func persistentlyEmptyPrimaryUsesTheAlternateHomepageAndCompletesMissingShelves() async throws {
        let alternate = try fixtureBody("copy-home-alternate")
        let feed = "{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}"
        let fixture = CopyFixture { request, backup in
            if backup { return .json(alternate) }
            return .json(request.url!.path.hasSuffix("homeIndex") ? #"{"code":200,"results":{"recComics":{"list":[]},"banners":[]}}"# : feed)
        }
        let home = try await fixture.client.home()
        #expect(home.features.count == 5)
        #expect(home.sections.contains { $0.id == "newComics" } && home.sections.contains { $0.id == "finishComics" })
        #expect(Set(home.rankings.map(\.period)) == Set(["day", "week", "month"]))
        #expect(home.notice == "已使用备用首页接口。")
        #expect(home.diagnostics?.contains("条目0") == true)
        #expect(fixture.requests.filter { $0.url?.host == fixture.content.host }.count == 1)
        #expect(fixture.requests.count == 10)
    }

    @Test func realToLoveVolumesKeepTheirSeriesAndNumericReaderOrder() async throws {
        let bundle = Bundle(for: CopyFixtureProtocol.self)
        let address = try #require(bundle.url(forResource: "copy-tolove-chapters", withExtension: "json") ??
            bundle.url(forResource: "copy-tolove-chapters", withExtension: "json", subdirectory: "Fixtures"))
        let body = try String(contentsOf: address, encoding: .utf8)
        let fixture = CopyFixture { request, _ in
            request.url!.path.contains("/comic2/") ? .json("{\"code\":200,\"results\":{\"comic\":\(comic),\"groups\":{\"default\":{\"name\":\"默認\"}}}}") : .json(body)
        }
        let chapters = try await fixture.client.chapters(key: "chubaowangnv")
        let expected = (1...18).reversed().map { String(format: "D%02d卷", $0) } +
            (1...18).reversed().map { String(format: "第%02d卷", $0) }
        #expect(chapters.filter { $0.volumeNumber != nil }.compactMap(\.title) == expected)
        #expect(ChapterSortOption.sourceOrder.sorted(chapters, ascending: true).first?.title == "第01卷")
        #expect(ChapterSortOption.chapter.sorted(chapters, ascending: true).filter { $0.volumeNumber != nil }.compactMap(\.title) == Array(expected.reversed()))
        #expect(ChapterSortOption.chapter.sorted(chapters, ascending: false).filter { $0.volumeNumber != nil }.compactMap(\.title) == expected)
        #expect(ChapterSortOption.uploadDate.sorted(chapters, ascending: false).map(\.key) == chapters.map(\.key))
    }

    @Test func searchTimeoutUsesTheVerifiedAlternateWithoutLosingQueryOrPage() async throws {
        let body = "{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":32,\"offset\":30,\"limit\":30}}"
        let fixture = CopyFixture { _, backup in backup ? .json(body) : .failure(URLError(.timedOut)) }
        let page = try await fixture.client.catalog(CopyQuery(kind: "search", keyword: " 金牌得主 & 漫画 "), page: 2)
        #expect(page.entries.count == 1 && page.more)
        #expect(fixture.requests.count == 3)
        let request = try #require(fixture.requests.last)
        #expect(request.url?.host == fixture.content.host)
        #expect(request.url?.path == "/api/kb/web/searchcl/comics")
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
        #expect(query.first { $0.name == "q" }?.value == "金牌得主 & 漫画")
        #expect(query.first { $0.name == "offset" }?.value == "30")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func reading429WaitsForRetryAfterAndTheRecoveredPagesAreCached() async throws {
        let counter = CopyAttemptCounter()
        let body = #"{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}"#
        let fixture = CopyFixture { _, _ in counter.next() == 1 ? .response(429, "{}", ["Retry-After": "1"]) : .json(body) }
        let client = fixture.client
        let started = Date()
        let pages = try await client.pages(key: "modujingbingdenuli", chapter: "188")
        #expect(Date().timeIntervalSince(started) >= 0.9)
        #expect(pages == ["https://images.example/page.webp"])
        #expect(try await client.pages(key: "modujingbingdenuli", chapter: "188") == pages)
        #expect(fixture.requests.count == 2)
    }

    @Test func longRetryAfterStopsOtherChaptersAndDoesNotChangeService() async throws {
        let fixture = CopyFixture { _, _ in .response(429, "{}", ["Retry-After": "90"]) }
        let client = fixture.client
        for chapter in ["188", "187"] {
            do { _ = try await client.pages(key: "modujingbingdenuli", chapter: chapter); Issue.record("Rate limit was ignored") }
            catch CopyError.rateLimited(let until) { #expect(until.timeIntervalSinceNow > 85) }
        }
        #expect(fixture.requests.count == 1)
    }

    @Test func unlabelledJsonRateLimitsUseBoundedBackoff() async throws {
        let fixture = CopyFixture { _, _ in .json(#"{"code":429,"results":null}"#) }
        do { _ = try await fixture.client.pages(key: "manga", chapter: "188"); Issue.record("JSON rate limit became a success") }
        catch CopyError.rateLimited { #expect(fixture.requests.count == 3) }
    }

    @Test func retryAfterHttpDateUsesTheServerClockAndZeroCannotBusyLoop() {
        #expect(CopyRequestPolicy.retryDelay("Sat, 10 Oct 2026 11:01:30 GMT", serverDate: "Sat, 10 Oct 2026 11:00:00 GMT", now: .distantPast) == 90)
        #expect(CopyRequestPolicy.retryDelay("0") == 1)
        #expect(CopyRequestPolicy.retryDelay("bad-date") == nil)
    }

    @Test func concurrentReadersAndDownloadsShareOnePageListRequest() async throws {
        let fixture = CopyFixture { _, _ in .json(#"{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}"#) }
        let client = fixture.client
        let pages = try await withThrowingTaskGroup(of: [String].self) { group in
            for _ in 0..<4 { group.addTask { try await client.pages(key: "manga", chapter: "188") } }
            var results: [[String]] = []
            for try await result in group { results.append(result) }
            return results
        }
        #expect(pages.count == 4 && pages.allSatisfy { $0 == ["https://images.example/page.webp"] })
        #expect(fixture.requests.count == 1)
    }

    @Test func permissionFailuresAreNotRetriedOrCachedAsPages() async throws {
        let counter = CopyAttemptCounter()
        let fixture = CopyFixture { _, _ in counter.next() == 1 ? .status(403) :
            .json(#"{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}"#) }
        let client = fixture.client
        do { _ = try await client.pages(key: "manga", chapter: "188"); Issue.record("403 was ignored") }
        catch CopyError.http(let status, _) { #expect(status == 403 && fixture.requests.count == 1) }
        #expect(try await client.pages(key: "manga", chapter: "188") == ["https://images.example/page.webp"])
        #expect(fixture.requests.count == 2)
    }

    @Test func validHomeUsesOneRequestAndBypassesCachedEmptyResponses() async throws {
        let body = "{\"code\":200,\"results\":{\"recComics\":{\"list\":[\(comic)]}}}"
        let fixture = CopyFixture { _, _ in .json(body) }
        let home = try await fixture.client.home()
        #expect(home.sections.first?.entries.first?.id == "fixture-comic")
        #expect(home.notice == nil)
        #expect(fixture.requests.count == 1)
        #expect(fixture.requests.first?.cachePolicy == .reloadIgnoringLocalCacheData)
        #expect(fixture.requests.first?.value(forHTTPHeaderField: "Cache-Control") == "no-cache")
    }

    @Test func mixedNullEntriesDoNotDiscardTheWholeList() throws {
        let data = Data("{\"recComics\":{\"list\":[null,\(comic),4]}}".utf8)
        let value = try JSONSerialization.jsonObject(with: data)
        let json = try #require(value as? [String: Any])
        let home = try CopyClient().parseHome(json)
        #expect(home.sections.first?.entries.map(\.id) == ["fixture-comic"])
    }

    @Test func transientEmptyHomeRetriesWithoutReplacingOriginalRecommendations() async throws {
        let body = "{\"code\":200,\"results\":{\"recComics\":{\"list\":[\(comic)]}}}"
        let counter = CopyAttemptCounter()
        let fixture = CopyFixture { _, _ in counter.next() == 1 ? .json(#"{"code":200,"results":{}}"#) : .json(body) }
        let home = try await fixture.client.home()
        #expect(home.sections.first?.entries.first?.id == "fixture-comic")
        #expect(home.notice == nil)
        #expect(fixture.requests.count == 2)
    }

    @Test func transientTlsFailureRetriesBeforeChangingService() async throws {
        let body = "{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}"
        let counter = CopyAttemptCounter()
        let fixture = CopyFixture { _, _ in counter.next() == 1 ? .failure(URLError(.secureConnectionFailed)) : .json(body) }
        let result = try await fixture.client.catalog(.init(), page: 1)
        #expect(result.entries.first?.id == "fixture-comic")
        #expect(fixture.requests.count == 2)
        #expect(fixture.requests.allSatisfy { $0.url?.host == fixture.browse.host })
    }

    @Test func emptyAndUnknownHomeShapesAreErrors() {
        let responses: [[String: Any]] = [[:], ["recComics": ["list": []]], ["unexpected": ["list": []]]]
        for json in responses {
            #expect(throws: (any Error).self) { try CopyClient().parseHome(json) }
        }
    }

    @Test func emptyHomepageRecoversThroughIndependentFeeds() async throws {
        let catalog = "{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}"
        let fixture = CopyFixture { request, _ in
            if request.url?.path == "/api/v3/h5/homeIndex" { return .json(#"{"code":200,"results":{}}"#) }
            return .json(catalog)
        }
        let home = try await fixture.client.home()
        #expect(home.sections.map(\.id) == ["newComics", "hotComics", "finishComics"])
        #expect(home.rankings.map(\.period) == ["day", "week", "month"])
        #expect(home.diagnostics?.contains("空对象") == true)
        #expect(home.notice != nil)
        #expect(fixture.requests.count == 10)
    }

    @Test func failureOfAllFeedsCannotBecomeBlankSuccess() async {
        let fixture = CopyFixture { _, _ in .json(#"{"code":200,"results":{}}"#) }
        do {
            _ = try await fixture.client.home()
            Issue.record("An empty home must throw")
        } catch {
            #expect(error.localizedDescription.contains("首页加载失败"))
        }
    }

    @Test func tlsFailureRetriesTheVerifiedServiceAndPreservesFilters() async throws {
        let body = "{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}"
        let fixture = CopyFixture { _, backup in backup ? .json(body) : .failure(URLError(.secureConnectionFailed)) }
        let page = try await fixture.client.catalog(.init(kind: "ranking", rankPeriod: "month", audience: "female"), page: 1)
        #expect(page.entries.first?.id == "fixture-comic")
        let backup = try #require(fixture.requests.last)
        let parameters = URLComponents(url: try #require(backup.url), resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(parameters.contains { $0.name == "date_type" && $0.value == "month" })
        #expect(parameters.contains { $0.name == "audience_type" && $0.value == "female" })
        #expect(backup.url?.scheme == "https")
        #expect(backup.value(forHTTPHeaderField: "version") == "2024.4.28")
        #expect(backup.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(fixture.requests.count == 3)
    }

    @Test func finishedFilterCannotSilentlyFallBackToAnUnfilteredCatalog() async {
        let fixture = CopyFixture { _, backup in
            backup ? .json("{\"code\":200,\"results\":{\"list\":[\(comic)],\"total\":1}}") : .failure(URLError(.secureConnectionFailed))
        }
        do {
            _ = try await fixture.client.catalog(.init(kind: "finished"), page: 1)
            Issue.record("The legacy catalog doesn't implement this filter")
        } catch {
            #expect(error.localizedDescription.contains("备用接口不支持这个筛选条件"))
            #expect(fixture.requests.allSatisfy { $0.url?.host == fixture.browse.host })
        }
    }

    @Test func bothTlsFailuresRemainVisible() async {
        let fixture = CopyFixture { _, _ in .failure(URLError(.serverCertificateUntrusted)) }
        do {
            _ = try await fixture.client.catalog(.init(), page: 1)
            Issue.record("TLS failure must remain visible")
        } catch {
            #expect(error.localizedDescription.contains("安全连接失败（TLS"))
            #expect(error.localizedDescription.contains("备用 HTTPS"))
            #expect(fixture.requests.count == 2)
        }
    }

    @Test func cancellationDoesNotStartFallbackRequests() async {
        let fixture = CopyFixture { _, _ in .failure(URLError(.cancelled)) }
        do {
            _ = try await fixture.client.home()
            Issue.record("Cancellation must propagate")
        } catch {
            #expect(error is CancellationError)
            #expect(fixture.requests.count == 1)
        }
    }

    @Test func bookstoreSessionAllowsCellularAndLowDataMode() {
        let config = CopyClient.networkSession.configuration
        #expect(config.allowsCellularAccess)
        #expect(config.allowsExpensiveNetworkAccess)
        #expect(config.allowsConstrainedNetworkAccess)
    }

    @Test func cellularRestrictionsAreNotMisreportedAsCertificateFailures() async {
        let failure = URLError(.notConnectedToInternet,
            userInfo: [NSURLErrorNetworkUnavailableReasonKey: URLError.NetworkUnavailableReason.cellular.rawValue])
        let fixture = CopyFixture { _, _ in .failure(failure) }
        do {
            _ = try await fixture.client.catalog(.init(), page: 1)
            Issue.record("Cellular restriction must remain visible")
        } catch {
            #expect(error.localizedDescription.contains("蜂窝权限"))
            #expect(error.localizedDescription.contains("-1009"))
            #expect(!error.localizedDescription.contains("安全连接失败"))
        }
    }

    @Test func httpAuthorizationFailuresAreNotRetriedAsTlsFailures() async {
        let fixture = CopyFixture { _, _ in .status(401) }
        do {
            _ = try await fixture.client.catalog(.init(), page: 1)
            Issue.record("HTTP 401 must propagate")
        } catch {
            #expect(fixture.requests.count == 1)
        }
    }
}
