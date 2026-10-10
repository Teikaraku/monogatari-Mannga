import Foundation
import Testing
@testable import Monogatari

private final class CopyFixtureProtocol: URLProtocol, @unchecked Sendable {
    enum Reply { case json(String), failure(URLError), status(Int) }
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
        switch reply {
            case .json(let json): status = 200; text = json
            case .status(let code): status = code; text = "{}"
            case .failure: return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"])!
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

    var client: CopyClient { .init(session: session, browseBase: browse, contentBase: content) }

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
        #expect(fixture.requests.count == 9)
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
