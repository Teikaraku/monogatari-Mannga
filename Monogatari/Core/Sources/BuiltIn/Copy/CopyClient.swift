//
//  CopyClient.swift
//  Monogatari
//
//  JSON transport for the built-in 拷贝漫画 bookstore. It mirrors the Android client
//  (android/.../sources/CopySourceClient.kt) so both apps request and parse the same data.
//

import AidokuRunner
import Foundation

/// A comic as listed by a feed, with the feed-specific details the bookstore displays.
struct CopyComic: Identifiable, Hashable, Sendable {
    let manga: AidokuRunner.Manga
    var latestChapter: String?
    var rank: Int?
    var previousRank: Int?
    var heat: Int?

    var id: String { manga.key }
}

struct CopySection: Identifiable, Sendable {
    let id: String
    let title: String
    let entries: [CopyComic]
}

struct CopyFeature: Identifiable, Sendable {
    let title: String
    let image: String
    let comic: CopyComic

    var id: String { comic.id + image }
}

struct CopyTopic: Identifiable, Hashable, Sendable {
    let key: String
    let title: String
    let image: String
    var subtitle: String = ""
    var intro: String = ""

    var id: String { key }

    /// Listing covers are resized variants (`name.jpg.200x80.jpg`); banners use the original.
    var fullImage: String {
        image.replacingOccurrences(
            of: #"(\.(?:jpe?g|png|webp))\.\d+x\d+\.(?:jpe?g|png|webp)$"#,
            with: "$1",
            options: [.regularExpression, .caseInsensitive]
        )
    }
}

struct CopyRanking: Identifiable, Sendable {
    let period: String
    let title: String
    let entries: [CopyComic]

    var id: String { period }
}

struct CopyHome: Sendable {
    var features: [CopyFeature] = []
    var sections: [CopySection] = []
    var topics: [CopyTopic] = []
    var rankings: [CopyRanking] = []
    var notice: String?
    var diagnostics: String?
}

struct CopyPage<T: Sendable>: Sendable {
    let entries: [T]
    let more: Bool
}

struct CopyQuery: Hashable, Sendable {
    var kind = "catalog"
    var keyword = ""
    var theme = ""
    var order = "-datetime_updated"
    var rankPeriod = "day"
    var audience = "male"
    var topic = ""
}

enum CopyError: LocalizedError {
    case message(String)
    case http(Int, String)
    case emptyHome(String)
    case rateLimited(Date)

    var errorDescription: String? {
        switch self {
            case .message(let message): message
            case .http(_, let message): message
            case .emptyHome(let message): message
            case .rateLimited(let until):
                "拷贝漫画请求过于频繁（HTTP 429），请在约 \(String(format: "%.0f", ceil(max(1, until.timeIntervalSinceNow)))) 秒后重试。代理的共享出口也可能被限流。"
        }
    }
}

struct CopyClient: Sendable {
    static let sourceKey = "copy2026"
    static let name = "拷贝漫画"
    static let website = URL(string: "https://www.2026copy.com")!
    static let mainlandWebsite = URL(string: "https://www.copy4000.com")!
    static let api = URL(string: "https://api.copy2000.online")!
    /// The API domain the official app currently uses; same catalog, rankings, topics and filters.
    static let apiAlternate = URL(string: "https://api.copy202601.com")!
    static let apiVersion = "3.0.9"
    static let contentApi = URL(string: "https://www.manga2026.xyz")!
    static let contentBackup = URL(string: "https://mapi.hotmangasg.com")!
    static let contentVersion = "2024.4.28"
    // Bookstore requests are user initiated, including on cellular or Low Data Mode.
    static let networkSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.allowsCellularAccess = true
        config.allowsExpensiveNetworkAccess = true
        config.allowsConstrainedNetworkAccess = true
        return URLSession(configuration: config)
    }()

    private let session: URLSession
    private let browseBase: URL
    private let browseAlternates: [URL]
    private let browseRoute: CopyContentRoute
    private let contentBase: URL
    private let contentAlternates: [URL]
    private let contentRoute: CopyContentRoute
    private let searchBases: [URL]
    private let searchApiBase: URL?
    private let searchRoutes = CopySearchRoutes()
    private static let sharedPolicy = CopyRequestPolicy()
    private static let sharedBrowseRoute = CopyContentRoute()
    private static let health = CopyRouteHealth()
    private let policy = Self.sharedPolicy
    private let readCache = CopyReadCache()
    private let metadataCache = CopyMetadataCache()

    init(session: URLSession = Self.networkSession, browseBase: URL = Self.api, contentBase: URL = Self.contentApi,
         searchBases: [URL]? = nil, contentAlternates: [URL]? = nil, searchApiBase: URL? = nil, browseAlternates: [URL]? = nil) {
        self.session = session
        self.browseBase = browseBase
        self.browseAlternates = browseAlternates ?? (browseBase == Self.api ? [Self.apiAlternate] : [])
        self.browseRoute = browseBase == Self.api ? Self.sharedBrowseRoute : CopyContentRoute()
        self.contentBase = contentBase
        self.searchBases = searchBases ?? [Self.mainlandWebsite, Self.website]
        self.searchApiBase = searchApiBase ?? (searchBases == nil ? Self.contentBackup : nil)
        self.contentAlternates = contentAlternates ?? (contentBase.host == Self.contentApi.host ? [Self.contentBackup] : [])
        self.contentRoute = CopyContentRoute(preferred: contentAlternates == nil && contentBase == Self.contentApi ? Self.contentBackup : nil)
    }

    static let genres: [(id: String, name: String)] = [
        ("aiqing", "愛情"), ("huanlexiang", "歡樂向"), ("maoxian", "冒險"), ("qihuan", "奇幻"),
        ("baihe", "百合"), ("xiaoyuan", "校园"), ("kehuan", "科幻"), ("dongfang", "東方"),
        ("danmei", "耽美"), ("gedou", "格鬥"), ("tl", "TL"), ("shenghuo", "生活"),
        ("qingxiaoshuo", "轻小说"), ("qita", "其他"), ("xuanyi", "悬疑"), ("mengxi", "萌系"),
        ("shengui", "神鬼"), ("zhichang", "职场"), ("zhiyu", "治愈"), ("jiecao", "节操"),
        ("sige", "四格"), ("gaoxiao", "搞笑"), ("changtiao", "長條"), ("jingji", "竞技"),
        ("mohuan", "魔幻"), ("jianniang", "舰娘"), ("weiniang", "伪娘"), ("rexue", "热血"),
        ("jingsong", "惊悚"), ("meishi", "美食"), ("xingzhuanhuan", "性转换"), ("lizhi", "励志"),
        ("yinyuewudao", "音乐舞蹈"), ("hougong", "後宮"), ("zhentan", "侦探"), ("caise", "彩色"),
        ("aa", "AA"), ("yishijie", "异世界"), ("lishi", "历史"), ("chuanyue", "穿越"),
        ("jizhan", "机战"), ("zhanzheng", "战争"), ("dushi", "都市"), ("chongsheng", "重生"),
        ("comiket102", "C102"), ("kongbu", "恐怖"), ("shengcun", "生存"), ("comiket103", "C103"),
        ("comiket104", "C104"), ("comiket100", "C100"), ("wuxia", "武侠"), ("comiket101", "C101"),
        ("comiket99", "C99"), ("comiket97", "C97"), ("comiket105", "C105"), ("zhaixi", "宅系"),
        ("comiket96", "C96"), ("comiket106", "C106"), ("comiket107", "C107"), ("comiket98", "C98"),
        ("comiket95", "C95"), ("zhuansheng", "转生"), ("comiket108", "C108"), ("fate", "FATE"),
        ("Uncensored", "無修正"), ("xianxia", "仙侠"), ("loveLive", "LoveLive"), ("zazhifuzengxiezhenji", "雜誌附贈寫真集")
    ]

    private var userAgent: String {
        "Monogatari/\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1")"
    }

    // MARK: Requests

    private func url(_ base: URL, _ path: String, _ parameters: [(String, String)] = [], content: Bool = false) throws -> URL {
        guard var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw CopyError.message("无效漫画资源地址")
        }
        var items = parameters.map { URLQueryItem(name: $0.0, value: $0.1) }
        if content {
            items.insert(URLQueryItem(name: "platform", value: "3"), at: 0)
        }
        components.queryItems = items.isEmpty ? nil : items
        guard let url = components.url else { throw CopyError.message("无效漫画资源地址") }
        return url
    }

    private func result(_ original: URL, label: String, content: Bool = false, fallback: Bool = true) async throws -> [String: Any] {
        func rebased(_ url: URL, to base: URL) -> URL {
            var target = URLComponents(url: base, resolvingAgainstBaseURL: false)!
            let source = URLComponents(url: url, resolvingAgainstBaseURL: false)!
            target.percentEncodedPath = source.percentEncodedPath
            target.percentEncodedQuery = source.percentEncodedQuery
            return target.url!
        }
        func same(_ url: URL, _ base: URL) -> Bool { url.host == base.host && url.port == base.port }
        let browse = !content && same(original, browseBase)
        var candidates: [URL]
        if content, same(original, contentBase) {
            let preferred = await contentRoute.current(fallback: contentBase)
            candidates = fallback ? [preferred, contentBase] + contentAlternates : [preferred]
        } else if browse {
            let preferred = await browseRoute.current(fallback: browseBase)
            candidates = [preferred, browseBase] + browseAlternates
        } else {
            candidates = [original]
        }
        var routes: [URL] = []
        for candidate in candidates where !routes.contains(candidate) { routes.append(candidate) }
        // The legacy catalog silently ignores the modern collection/status filters.
        // Returning its unfiltered list would label ongoing books as finished.
        let filters = URLComponents(url: original, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let filtered = !content && original.path == "/api/v3/comics" && filters.contains {
            ["top", "status"].contains($0.name) && !($0.value ?? "").isEmpty
        }
        let legacy = browse && fallback && browseBase != contentBase && original.path.hasPrefix("/api/v3/")
        // Only Copy has the topic list, topic details and the filtered collections; the backup service answers them empty or unfiltered.
        let copyOnly = filtered || original.path == "/api/v3/topics" ||
            (original.path.hasPrefix("/api/v3/topic/") && !original.path.hasSuffix("/contents"))
        // A route that just failed to connect is tried last for a while, and skipped when the backup service can answer.
        var healthy: [URL] = []
        var cooling: [URL] = []
        for route in routes {
            if await Self.health.cooling(route) { cooling.append(route) } else { healthy.append(route) }
        }
        let ordered = healthy + (legacy && !copyOnly ? [] : cooling)
        var failures: [String] = []
        var timedOut = false
        // Resets on Copy's domains come and go within seconds, so what only Copy can answer gets one more quick round.
        let rounds = copyOnly ? 2 : 1
        for round in 0..<rounds {
            if round > 0 {
                if timedOut { break }
                try await Task.sleep(nanoseconds: 800_000_000)
            }
            for (index, base) in ordered.enumerated() {
                let target = base == original ? original : rebased(original, to: base)
                // A stalled route is left for the next one; only the last route gets a second, fresh attempt after a timeout.
                let lastRoute = round == rounds - 1 && index == ordered.count - 1 && !(legacy && !filtered)
                do {
                    let data = try await requestWithRetry(target, label: label, content: content, retryTimeout: lastRoute)
                    await Self.health.succeeded(base)
                    if content { await contentRoute.succeeded(base) } else if browse { await browseRoute.succeeded(base) }
                    return data
                } catch {
                    try Task.checkCancellation()
                    if error is CancellationError { throw error }
                    let failure = error as NSError
                    if failure.domain == NSURLErrorDomain && failure.code == URLError.Code.cancelled.rawValue { throw CancellationError() }
                    if case CopyError.rateLimited = error { throw error }
                    // Answers such as a refused permission are final; only connection and server failures change route.
                    let network = failure.domain == NSURLErrorDomain
                    var serverFailure = false
                    if case CopyError.http(let status, _) = error { serverFailure = status >= 500 }
                    guard network || serverFailure else { throw error }
                    if network { await Self.health.failed(base) }
                    if network && failure.code == URLError.Code.timedOut.rawValue { timedOut = true }
                    if round == 0 { failures.append(connectionFailure(target, label: label, error: error)) }
                }
            }
        }
        guard legacy else { throw CopyError.message(failures.joined(separator: "\n备用 HTTPS 接口：")) }
        if filtered {
            throw CopyError.message("筛选目录暂时连接失败，备用接口不支持这个筛选条件。请稍后重试或更换网络。\n" +
                failures.joined(separator: "\n备用 HTTPS 接口："))
        }
        var alternate = URLComponents(url: contentBase, resolvingAgainstBaseURL: false)!
        let source = URLComponents(url: original, resolvingAgainstBaseURL: false)!
        alternate.percentEncodedPath = source.percentEncodedPath
        alternate.queryItems = source.queryItems
        if !(alternate.queryItems ?? []).contains(where: { $0.name == "platform" }) {
            alternate.queryItems = (alternate.queryItems ?? []) + [URLQueryItem(name: "platform", value: "3")]
        }
        do {
            return try await result(alternate.url!, label: label, content: true)
        } catch let secondary {
            try Task.checkCancellation()
            if secondary is CancellationError { throw secondary }
            if case CopyError.rateLimited = secondary { throw secondary }
            let all = failures + [connectionFailure(alternate.url!, label: label, error: secondary)]
            throw CopyError.message(all.joined(separator: "\n备用 HTTPS 接口："))
        }
    }

    private func requestWithRetry(_ url: URL, label: String, content: Bool, retryTimeout: Bool = true) async throws -> [String: Any] {
        var attempt = 0
        while true {
            try await policy.before(url)
            do { return try await requestTransiently(url, label: label, content: content, rateAttempt: attempt, retryTimeout: retryTimeout) }
            catch CopyError.rateLimited(let until) {
                guard attempt < 2, until.timeIntervalSinceNow <= CopyRequestPolicy.maximumAutomaticWait else { throw CopyError.rateLimited(until) }
                attempt += 1
            }
        }
    }

    private func requestTransiently(_ url: URL, label: String, content: Bool, rateAttempt: Int, retryTimeout: Bool) async throws -> [String: Any] {
        do {
            return try await requestResult(url, label: label, content: content, rateAttempt: rateAttempt)
        } catch {
            try Task.checkCancellation()
            let failure = error as NSError
            let transient: [URLError.Code] = [.networkConnectionLost, .timedOut, .cannotConnectToHost,
                .dnsLookupFailed, .secureConnectionFailed]
            guard failure.domain == NSURLErrorDomain, transient.contains(where: { $0.rawValue == failure.code }) else { throw error }
            if !retryTimeout && failure.code == URLError.Code.timedOut.rawValue { throw error }
            try await Task.sleep(nanoseconds: 150_000_000)
            let fresh = URLSession(configuration: session.configuration)
            defer { fresh.invalidateAndCancel() }
            return try await requestResult(url, label: label, content: content, transport: fresh, rateAttempt: rateAttempt)
        }
    }

    private func connectionFailure(_ url: URL, label: String, error: Error) -> String {
        let failure = error as NSError
        let tlsCodes: [URLError.Code] = [.secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
            .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .clientCertificateRejected, .clientCertificateRequired]
        let reason: String
        if failure.domain == NSURLErrorDomain && tlsCodes.contains(where: { $0.rawValue == failure.code }) {
            reason = "安全连接失败（TLS，错误 \(failure.code)），请检查网络或稍后重试"
        } else if let unavailable = (error as? URLError)?.networkUnavailableReason, unavailable == .cellular {
            reason = "当前请求无法使用蜂窝数据，请在 iPhone 设置中检查物语的蜂窝权限（错误 \(failure.code)）"
        } else {
            reason = failure.domain == NSURLErrorDomain ? "\(error.localizedDescription)（错误 \(failure.code)）" : error.localizedDescription
        }
        return "拷贝漫画\(label)（\(url.host ?? "未知地址")）：\(reason)"
    }

    private func requestResult(_ url: URL, label: String, content: Bool, transport: URLSession? = nil, rateAttempt: Int = 0) async throws -> [String: Any] {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("zh-Hant,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue(content ? Self.contentVersion : Self.apiVersion, forHTTPHeaderField: "version")
        request.setValue("3", forHTTPHeaderField: "platform")
        if content {
            request.setValue("3", forHTTPHeaderField: "dnts")
            request.setValue("1", forHTTPHeaderField: "webp")
            request.setValue(Self.website.absoluteString, forHTTPHeaderField: "Referer")
        } else {
            request.setValue("0", forHTTPHeaderField: "region")
        }
        let (data, response) = try await (transport ?? session).data(for: request)
        guard data.count <= 20 * 1024 * 1024 else { throw CopyError.message("拷贝接口返回的数据过大") }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let message = (json?["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 429 || json?["code"] as? Int == 429 {
            let response = response as? HTTPURLResponse
            throw await policy.limited(url, retryAfter: response?.value(forHTTPHeaderField: "Retry-After"),
                serverDate: response?.value(forHTTPHeaderField: "Date"), attempt: rateAttempt)
        }
        guard (200..<300).contains(status) else {
            throw CopyError.http(status, "\(Self.name)\(label)失败：\(message ?? "HTTP \(status)")")
        }
        guard let json else { throw CopyError.message("\(Self.name)\(label)接口未返回 JSON 数据") }
        if let code = json["code"] as? Int, code != 200 {
            throw CopyError.message("\(Self.name)\(label)：\(message ?? "接口访问受限")")
        }
        guard let results = json["results"] as? [String: Any] else {
            throw CopyError.message("\(Self.name)暂未返回\(label)数据，可能存在接口或客户端访问限制。")
        }
        return results
    }

    // MARK: Feeds

    func home() async throws -> CopyHome {
        do {
            let json = try await result(url(browseBase, "api/v3/h5/homeIndex"), label: "首页", fallback: false)
            return try parseHome(json)
        } catch {
            try Task.checkCancellation()
            if error is CancellationError { throw error }
            if (error as NSError).domain == NSURLErrorDomain && (error as NSError).code == URLError.Code.cancelled.rawValue {
                throw CancellationError()
            }
            if case CopyError.rateLimited = error { throw error }
            if case CopyError.emptyHome = error {
                do {
                    let json = try await result(url(browseBase, "api/v3/h5/homeIndex", [("_update", "true"), ("_", String(Int64(Date().timeIntervalSince1970 * 1000)))]), label: "首页", fallback: false)
                    return try parseHome(json)
                } catch {
                    try Task.checkCancellation()
                    if error is CancellationError { throw error }
                    if (error as NSError).domain == NSURLErrorDomain && (error as NSError).code == URLError.Code.cancelled.rawValue { throw CancellationError() }
                    if case CopyError.rateLimited = error { throw error }
                    return try await recoverHome(error)
                }
            }
            return try await recoverHome(error)
        }
    }

    func parseHome(_ json: [String: Any]) throws -> CopyHome {
        var home = CopyHome()
        let definitions = [
            ("recComics", "为你推荐"), ("newComics", "新书入库"), ("hotComics", "热门更新"),
            ("rankDayComics", "今日热门"), ("finishComics", "完结佳作")
        ]
        home.sections = definitions.compactMap { key, title in
            let entries = comics(json[key])
            return entries.isEmpty ? nil : CopySection(id: key, title: title, entries: entries)
        }
        home.features = items(json["banners"]).compactMap { banner in
            guard (banner["type"] as? Int) == 1, let raw = banner["comic"] as? [String: Any], Self.isFree(raw),
                  let entry = comic(raw) else { return nil }
            let brief = (banner["brief"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? entry.manga.title
            return CopyFeature(title: brief, image: webUrl(banner["cover"] as? String), comic: entry)
        }
        let topics = items(json["topicsList"])
        home.topics = (topics.isEmpty ? items(json["topics"]) : topics).map(topic)
        let periods = [("day", "日榜", "rankDayComics"), ("week", "周榜", "rankWeekComics"), ("month", "月榜", "rankMonthComics")]
        home.rankings = periods.compactMap { period, title, key in
            let entries = comics(json[key])
            return entries.isEmpty ? nil : CopyRanking(period: period, title: title, entries: entries)
        }
        guard !home.sections.isEmpty || !home.features.isEmpty || !home.rankings.isEmpty else {
            let schema = Self.homeSchema(json)
            throw CopyError.emptyHome("首页返回成功，但没有可显示的漫画。返回结构：\(schema.isEmpty ? "空对象 {}" : schema)")
        }
        return home
    }

    static func homeSchema(_ json: [String: Any]) -> String {
        let client = CopyClient()
        return json.keys.sorted().prefix(16).map { key in
            let value = json[key]
            let entries = client.items(value)
            let shape: String
            if let object = value as? [String: Any] { shape = "对象(\(object.keys.sorted().joined(separator: ",")))" }
            else if value is [Any] { shape = "数组" }
            else if value is String { shape = "字符串" }
            else if value is NSNull { shape = "null" }
            else { shape = String(describing: type(of: value as Any)) }
            let sample = entries.first
            let comic = sample?["comic"] as? [String: Any] ?? sample
            let valid = entries.compactMap(client.comic).count
            return "\(key)=\(shape)，条目\(entries.count)，有效漫画\(valid)" + (comic.map {
                "，漫画字段(\($0.keys.sorted().joined(separator: ",")))，path_word=\(String(describing: type(of: $0["path_word"] as Any)))"
            } ?? "")
        }.joined(separator: "；")
    }

    private struct RecoveredFeed: Sendable {
        let id: String
        var section: CopySection?
        var ranking: CopyRanking?
        var topics: [CopyTopic] = []
        var error: String?
    }

    private func recoverHome(_ primary: Error) async throws -> CopyHome {
        let requests: [(String, String, CopyQuery)] = [
            ("newComics", "新书入库", .init(order: "-datetime_created")),
            ("hotComics", "热门更新", .init(order: "-datetime_updated")),
            ("finishComics", "完结佳作", .init(kind: "finished")),
            ("day", "日榜", .init(kind: "ranking", rankPeriod: "day")),
            ("week", "周榜", .init(kind: "ranking", rankPeriod: "week")),
            ("month", "月榜", .init(kind: "ranking", rankPeriod: "month")),
            ("topics", "精选专题", .init(kind: "topics"))
        ]
        let feeds = await withTaskGroup(of: RecoveredFeed.self, returning: [RecoveredFeed].self) { group in
            for (id, title, query) in requests {
                group.addTask {
                    do {
                        if id == "topics" { return RecoveredFeed(id: id, topics: try await self.topics(page: 1).entries) }
                        let books = try await self.catalog(query, page: 1).entries
                        if id.hasSuffix("Comics") {
                            return RecoveredFeed(id: id, section: books.isEmpty ? nil : .init(id: id, title: title, entries: books))
                        }
                        return RecoveredFeed(id: id, ranking: books.isEmpty ? nil : .init(period: id, title: title, entries: books))
                    } catch {
                        return RecoveredFeed(id: id, error: "\(title)：\(error.localizedDescription)")
                    }
                }
            }
            var feeds: [RecoveredFeed] = []
            for await feed in group { feeds.append(feed) }
            return feeds
        }
        try Task.checkCancellation()
        let ordered = requests.compactMap { request in feeds.first { $0.id == request.0 } }
        var home = CopyHome()
        home.sections = ordered.compactMap(\.section)
        home.rankings = ordered.compactMap(\.ranking)
        home.topics = ordered.first { $0.id == "topics" }?.topics ?? []
        let failures = ordered.compactMap(\.error)
        let diagnostics = (["首页接口：\(primary.localizedDescription)"] + failures).joined(separator: "\n")
        guard !home.sections.isEmpty || !home.rankings.isEmpty else {
            throw CopyError.message("首页加载失败，请稍后重试。\n\(diagnostics)")
        }
        if !failures.isEmpty {
            home.notice = "部分栏目加载失败。"
            home.diagnostics = diagnostics
        }
        return home
    }

    func catalog(_ query: CopyQuery, page: Int) async throws -> CopyPage<CopyComic> {
        let limit = 30
        let offset = String((page - 1) * limit)
        let json: [String: Any]
        switch query.kind {
            case "search":
                let keyword = query.keyword.trimmingCharacters(in: .whitespacesAndNewlines)
                if keyword.isEmpty { return CopyPage(entries: [], more: false) }
                json = try await search(keyword, limit: limit, offset: offset, page: page)
            case "ranking":
                json = try await result(url(browseBase, "api/v3/ranks", [
                    ("limit", String(limit)), ("offset", offset), ("free_type", "1"), ("type", "1"),
                    ("audience_type", query.audience), ("date_type", query.rankPeriod)
                ]), label: "排行榜")
            case "topic":
                let topic = try pathKey(query.topic)
                json = try await result(url(browseBase, "api/v3/topic/\(topic)/contents", [
                    ("limit", String(limit)), ("offset", offset), ("free_type", "1")
                ]), label: "专题漫画")
            default:
                var parameters = [("limit", String(limit)), ("offset", offset), ("free_type", "1"), ("ordering", query.order)]
                if !query.theme.isEmpty { parameters.append(("theme", query.theme)) }
                if query.kind == "finished" { parameters.append(("top", "finish")) }
                json = try await result(url(browseBase, "api/v3/comics", parameters), label: "漫画目录")
        }
        return CopyPage(entries: comics(json), more: hasMore(json, count: items(json).count, page: page, limit: limit))
    }

    private func search(_ keyword: String, limit: Int, offset: String, page: Int) async throws -> [String: Any] {
        let selection = await searchRoutes.begin(keyword, restart: page == 1)
        if page > 1, searchApiBase != nil, selection.endpoint == nil { throw CopyError.message("搜索状态已失效，请重新搜索。") }
        let defaults = (searchApiBase.map { [CopySearchEndpoint(base: $0, api: true)] } ?? []) + searchBases.map { CopySearchEndpoint(base: $0, api: false) }
        let choices: [CopySearchEndpoint]
        if page > 1, let selected = selection.endpoint {
            choices = [selected] + defaults.filter { $0 != selected && $0.api == selected.api }
        } else { choices = defaults }
        var failures: [String] = []
        for (index, endpoint) in choices.enumerated() {
            var parameters = [("q", keyword), ("q_type", ""), ("platform", endpoint.api ? "3" : "2"), ("limit", String(limit)), ("offset", offset)]
            if endpoint.api { parameters.append(("free_type", "1")) }
            let address = try url(endpoint.base, endpoint.api ? "api/v3/search/comic" : "api/kb/web/searchcl/comics", parameters)
            do {
                let data = try await requestWithRetry(address, label: "搜索", content: endpoint.api, retryTimeout: index == choices.count - 1)
                try await searchRoutes.selected(keyword, token: selection.token, endpoint: endpoint)
                return data
            }
            catch {
                try Task.checkCancellation()
                if error is CancellationError { throw error }
                let failure = error as NSError
                if failure.domain == NSURLErrorDomain && failure.code == URLError.cancelled.rawValue { throw CancellationError() }
                let eligible: Bool
                if case CopyError.http(let code, _) = error { eligible = code == 404 || code == 408 || code >= 500 }
                else { eligible = failure.domain == NSURLErrorDomain }
                guard eligible else { throw error }
                failures.append(connectionFailure(address, label: "搜索", error: error))
                if index == choices.count - 1 { throw CopyError.message(failures.joined(separator: "\n")) }
            }
        }
        throw CopyError.message("没有可用的搜索地址")
    }

    func topics(page: Int) async throws -> CopyPage<CopyTopic> {
        let limit = 20
        let json = try await result(url(browseBase, "api/v3/topics", [("limit", String(limit)), ("offset", String((page - 1) * limit))]), label: "专题")
        let entries = items(json).map(topic)
        // An empty first page only comes from the backup service, which has no topics; it shows as a failure to retry.
        guard page > 1 || !entries.isEmpty else { throw CopyError.message("拷贝漫画暂未返回专题，请稍后重试。") }
        return CopyPage(entries: entries, more: hasMore(json, count: entries.count, page: page, limit: limit))
    }

    func topicDetail(_ key: String) async throws -> CopyTopic {
        let topicKey = try pathKey(key)
        let json = try await result(url(browseBase, "api/v3/topic/\(topicKey)"), label: "专题详情")
        return topic(json)
    }

    // MARK: Details

    func details(key: String) async throws -> AidokuRunner.Manga {
        try await snapshot(key: key).manga
    }

    private func snapshot(key: String) async throws -> CopyMangaSnapshot {
        let comicKey = try pathKey(key)
        let address = try url(contentBase, "api/v3/comic2/\(comicKey)", content: true)
        return try await metadataCache.value(address) {
            let json = try await self.result(address, label: "漫画详情", content: true)
            guard let raw = json["comic"] as? [String: Any], let entry = self.comic(raw) else { throw CopyError.message("\(Self.name)没有返回漫画详情") }
            guard Self.isFree(raw) else { throw CopyError.message("付费漫画已屏蔽") }
            var groups: [(String, String)] = []
            if let value = json["groups"] as? [String: Any] {
                groups = value.keys.sorted().map { groupKey in
                    ((value[groupKey] as? [String: Any])?["name"] as? String ?? groupKey, groupKey)
                }
                groups.sort { $0.1 == "default" && $1.1 != "default" }
            }
            if groups.isEmpty { groups = [("", "default")] }
            return CopyMangaSnapshot(manga: entry.manga, groups: groups)
        }
    }

    func chapters(key: String) async throws -> [AidokuRunner.Chapter] {
        let comicKey = try pathKey(key)
        let groups = try await snapshot(key: key).groups
        var chapters: [(chapter: AidokuRunner.Chapter, date: Double, group: Int, index: Double)] = []
        var seenKeys = Set<String>()
        for (groupIndex, group) in groups.enumerated() {
            let (groupName, groupKey) = group
            var offset = 0
            var seen = Set<String>()
            while true {
                let groupPath = try pathKey(groupKey)
                let data = try await result(url(contentBase, "api/v3/comic/\(comicKey)/group/\(groupPath)/chapters", [
                    ("limit", "100"), ("offset", String(offset)), ("_update", "true")
                ], content: true), label: "章节列表", content: true)
                let raw = items(data)
                let additions = raw.filter { row in
                    let id = (row["uuid"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? stringValue(row["id"])
                    return seen.insert(id).inserted
                }
                if !raw.isEmpty && additions.isEmpty { throw CopyError.message("拷贝章节分页重复，已停止加载") }
                for row in additions {
                    let id = (row["uuid"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? stringValue(row["id"])
                    guard !id.isEmpty, seenKeys.insert(id).inserted else { continue }
                    let type = row["type"] as? Int ?? 1
                    let title = row["name"] as? String ?? ""
                    let caption = Self.captionNumbers(title, type: type)
                    let number = type == 3 ? nil : (row["chapter"] as? Double) ?? caption.chapter
                    let volume = (row["volume"] as? Double) ?? caption.volume
                    let date = timestamp(row["datetime_created"] as? String)
                    let locked = (row["is_locked"] as? Bool) ?? (row["is_lock"] as? Bool) ?? false
                    let chapter = AidokuRunner.Chapter(
                        key: id,
                        title: title,
                        chapterNumber: number.map { Float($0) },
                        volumeNumber: volume.map { Float($0) },
                        dateUploaded: date > 0 ? Date(timeIntervalSince1970: date) : nil,
                        scanlators: groupName.isEmpty ? nil : [groupName],
                        url: Self.website.appendingPathComponent("comic/\(key)/chapter/\(id)"),
                        language: "zh",
                        locked: locked
                    )
                    chapters.append((chapter, date, groupIndex, (row["index"] as? Double) ?? Double(chapters.count)))
                }
                offset += raw.count
                let total = data["total"] as? Int ?? offset
                if raw.isEmpty || offset >= total || chapters.count >= 100_000 { break }
            }
        }
        return chapters.sorted { lhs, rhs in
            if lhs.date != rhs.date { return lhs.date > rhs.date }
            if lhs.group != rhs.group { return lhs.group < rhs.group }
            return lhs.index > rhs.index
        }.map { $0.chapter }
    }

    func pages(key: String, chapter: String) async throws -> [String] {
        let comicKey = try pathKey(key)
        let chapterKey = try pathKey(chapter)
        let address = try url(contentBase, "api/v3/comic/\(comicKey)/chapter/\(chapterKey)", content: true)
        return try await readCache.pages(address) {
            let data = try await self.result(address, label: "阅读图片", content: true)
            if (data["is_lock"] as? Bool) == true { throw CopyError.message("该章节需要拷贝账号的阅读权限") }
            guard let content = data["chapter"] as? [String: Any] else { throw CopyError.message("\(Self.name)未返回章节图片") }
            return try Self.orderedPages(content).map { self.webUrl($0) }
        }
    }

    // MARK: Parsing

    func comic(_ raw: [String: Any]) -> CopyComic? {
        let json = raw["comic"] as? [String: Any] ?? raw
        let key = (json["path_word"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? stringValue(json["uuid"])
        guard !key.isEmpty else { return nil }
        let statusValue = (json["status"] as? [String: Any])?["value"] as? Int ?? json["status"] as? Int
        let authors = items(json["author"]).compactMap { $0["name"] as? String }
        let tags = items(json["theme"]).compactMap { $0["name"] as? String }
        let manga = AidokuRunner.Manga(
            sourceKey: Self.sourceKey,
            key: key,
            title: json["name"] as? String ?? "",
            cover: webUrl(json["cover"] as? String),
            authors: authors.isEmpty ? nil : authors,
            description: (json["brief"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            url: Self.website.appendingPathComponent("comic/\(key)"),
            tags: tags.isEmpty ? nil : tags,
            status: statusValue == 0 ? .ongoing : (statusValue == 1 || statusValue == 2) ? .completed : .unknown,
            contentRating: .safe,
            viewer: .rightToLeft
        )
        var entry = CopyComic(manga: manga)
        // Update feeds wrap the comic with the chapter that was just published.
        if raw["comic"] != nil, let name = raw["name"] as? String, !name.isEmpty { entry.latestChapter = name }
        if entry.latestChapter == nil, let name = json["last_chapter_name"] as? String, !name.isEmpty { entry.latestChapter = name }
        entry.rank = raw["sort"] as? Int
        entry.previousRank = raw["sort_last"] as? Int
        entry.heat = (raw["rise_num"] as? Int) ?? (json["popular"] as? Int)
        return entry
    }

    private func topic(_ json: [String: Any]) -> CopyTopic {
        let key = (json["path_word"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? json["path"] as? String ?? ""
        let subtitle = [json["period"] as? String, json["datetime_created"] as? String]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        let intro = (json["intro"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? json["brief"] as? String ?? ""
        return CopyTopic(key: key, title: json["title"] as? String ?? "", image: webUrl(json["cover"] as? String), subtitle: subtitle, intro: intro)
    }

    /// Paid titles (free_type 2) are R18 and need an account; the bookstore only lists free comics.
    static func isFree(_ raw: [String: Any]) -> Bool {
        let json = raw["comic"] as? [String: Any] ?? raw
        return ((json["free_type"] as? [String: Any])?["value"] as? Int) != 2
    }

    private func comics(_ value: Any?) -> [CopyComic] {
        unique(items(value).filter(Self.isFree).compactMap(comic))
    }

    private func items(_ value: Any?) -> [[String: Any]] {
        if let array = value as? [Any] { return array.compactMap { $0 as? [String: Any] } }
        if let object = value as? [String: Any], let array = object["list"] as? [Any] {
            return array.compactMap { $0 as? [String: Any] }
        }
        return []
    }

    private func unique(_ comics: [CopyComic]) -> [CopyComic] {
        var seen = Set<String>()
        return comics.filter { seen.insert($0.id).inserted }
    }

    private func hasMore(_ data: [String: Any], count: Int, page: Int, limit: Int) -> Bool {
        let offset = data["offset"] as? Int ?? (page - 1) * limit
        let actualLimit = data["limit"] as? Int ?? limit
        guard count > 0 else { return false }
        if let total = data["total"] as? Int { return offset + count < total }
        return count >= actualLimit
    }

    private func webUrl(_ address: String?) -> String {
        guard let address, !address.isEmpty else { return "" }
        return URL(string: address, relativeTo: Self.website).map { Self.imageUrl($0).absoluteString } ?? ""
    }

    private func pathKey(_ key: String) throws -> String {
        guard !key.isEmpty, key.count <= 256, !key.contains("/"), !key.contains("\\"), key != ".", key != ".." else {
            throw CopyError.message("无效漫画标识")
        }
        return key
    }

    private func stringValue(_ value: Any?) -> String {
        switch value {
            case let string as String: string
            case let number as NSNumber: number.stringValue
            default: ""
        }
    }

    private func timestamp(_ value: String?) -> Double {
        guard let value, !value.isEmpty else { return 0 }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 8 * 3600)
        for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"] {
            formatter.dateFormat = format
            if let date = formatter.date(from: value) { return date.timeIntervalSince1970 }
        }
        return 0
    }

    /// Chapter and volume numbers written in the title, including Chinese numerals.
    static func captionNumbers(_ title: String, type: Int) -> (chapter: Double?, volume: Double?) {
        if type == 3 { return (nil, nil) }
        let digits: [Character: Int] = [
            "零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "兩": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9
        ]
        let units: [Character: Int] = ["十": 10, "百": 100, "千": 1000, "万": 10000, "萬": 10000]
        func number(_ raw: String) -> Double? {
            if let value = Double(raw) { return value }
            if !raw.contains(where: { units[$0] != nil }) {
                var text = ""
                for char in raw {
                    guard let digit = digits[char] else { return nil }
                    text += String(digit)
                }
                return Double(text)
            }
            var total = 0, section = 0, digit = 0
            for char in raw {
                if let value = digits[char] {
                    digit = value
                } else if let unit = units[char] {
                    if unit == 10000 {
                        total += max(section + digit, 1) * unit
                        section = 0
                    } else {
                        section += max(digit, 1) * unit
                    }
                    digit = 0
                } else {
                    return nil
                }
            }
            return Double(total + section + digit)
        }
        var chapter: Double?
        var volume: Double?
        let pattern = #"(?:第\s*)?([0-9]+(?:\.[0-9]+)?|[〇零一二两兩三四五六七八九十百千万萬]+)\s*([话話回章卷巻册冊])"#
        if let regex = try? NSRegularExpression(pattern: pattern) {
            let range = NSRange(title.startIndex..., in: title)
            for match in regex.matches(in: title, range: range) {
                guard let valueRange = Range(match.range(at: 1), in: title), let kindRange = Range(match.range(at: 2), in: title) else { continue }
                let value = number(String(title[valueRange]))
                if ["卷", "巻", "册", "冊"].contains(String(title[kindRange])) { volume = value } else { chapter = value }
            }
        }
        if chapter == nil && volume == nil, let value = Double(title.trimmingCharacters(in: .whitespaces)) {
            if type == 2 { volume = value } else { chapter = value }
        }
        return (chapter, volume)
    }

    /// The API supplies a permutation, not a list already in reading order.
    static func orderedPages(_ chapter: [String: Any]) throws -> [String] {
        let contents = (chapter["contents"] as? [[String: Any]] ?? []).compactMap { $0["url"] as? String }
        guard !contents.isEmpty else { throw CopyError.message("拷贝漫画暂未返回阅读图片") }
        guard let words = chapter["words"] as? [Any] else { return contents }
        guard words.count == contents.count else { throw CopyError.message("拷贝图片顺序数据不完整") }
        let order = words.compactMap { ($0 as? NSNumber)?.intValue }
        guard order.count == contents.count, Set(order) == Set(contents.indices) else { throw CopyError.message("拷贝图片顺序无效") }
        return contents.indices.sorted { order[$0] < order[$1] }.map { contents[$0] }
    }

    func imageRequest(_ url: URL) -> URLRequest {
        var request = URLRequest(url: Self.imageUrl(url))
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        request.setValue(Self.website.absoluteString, forHTTPHeaderField: "Referer")
        return request
    }

    func imageData(for original: URLRequest) async throws -> (Data, URLResponse) {
        var request = original
        guard let address = request.url else { throw CopyError.message("无效漫画图片地址") }
        request.url = Self.imageUrl(address)
        let url = request.url!
        var attempt = 0
        while true {
            try await policy.before(url)
            let fresh = attempt > 0
            let transport = fresh ? URLSession(configuration: session.configuration) : session
            defer { if fresh { transport.invalidateAndCancel() } }
            do {
                let (data, response) = try await transport.data(for: request)
                if let http = response as? HTTPURLResponse {
                    if http.statusCode == 429 {
                        throw await policy.limited(url, retryAfter: http.value(forHTTPHeaderField: "Retry-After"), serverDate: http.value(forHTTPHeaderField: "Date"), attempt: attempt)
                    }
                    guard (200..<300).contains(http.statusCode) else { throw CopyError.http(http.statusCode, "图片下载失败：HTTP \(http.statusCode)") }
                }
                guard data.count <= 50 * 1024 * 1024 else { throw CopyError.message("图片过大") }
                return (data, response)
            } catch {
                try Task.checkCancellation()
                let failure = error as NSError
                let transient: [URLError.Code] = [.networkConnectionLost, .timedOut, .cannotConnectToHost, .dnsLookupFailed, .secureConnectionFailed]
                let network = failure.domain == NSURLErrorDomain && transient.contains { $0.rawValue == failure.code }
                let limited: Bool
                if case CopyError.rateLimited(let until) = error { limited = until.timeIntervalSinceNow <= CopyRequestPolicy.maximumAutomaticWait }
                else { limited = false }
                guard attempt < 2, network || limited else { throw error }
                attempt += 1
            }
        }
    }

    static func imageUrl(_ url: URL) -> URL {
        guard url.scheme == "https", url.port == nil || url.port == 443, let host = url.host,
              host.range(of: #"^s[0-9a-z]\.mangafunb\.fun$"#, options: .regularExpression) != nil,
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        parts.host = host.replacingOccurrences(of: ".mangafunb.fun", with: ".mangafuna.xyz")
        return parts.url ?? url
    }

    static func isCopyImage(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", let host = url.host else { return false }
        return host.range(of: #"^s[0-9a-z]\.(?:mangafunb\.fun|mangafuna\.xyz)$"#, options: .regularExpression) != nil
    }
}
