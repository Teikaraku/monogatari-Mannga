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

    var errorDescription: String? {
        switch self {
            case .message(let message): message
        }
    }
}

struct CopyClient: Sendable {
    static let sourceKey = "copy2026"
    static let name = "拷贝漫画"
    static let website = URL(string: "https://www.2026copy.com")!
    static let api = URL(string: "https://api.copy2000.online")!
    static let apiVersion = "3.0.9"
    static let contentApi = URL(string: "https://www.manga2026.xyz")!
    static let contentVersion = "2024.4.28"

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

    private func result(_ url: URL, label: String, content: Bool = false) async throws -> [String: Any] {
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(content ? Self.contentVersion : Self.apiVersion, forHTTPHeaderField: "version")
        request.setValue("3", forHTTPHeaderField: "platform")
        if content {
            request.setValue("3", forHTTPHeaderField: "dnts")
            request.setValue("1", forHTTPHeaderField: "webp")
            request.setValue(Self.website.absoluteString, forHTTPHeaderField: "Referer")
        } else {
            request.setValue("0", forHTTPHeaderField: "region")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let message = (json?["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw CopyError.message("\(Self.name)\(label)失败：\(message ?? "HTTP \(status)")")
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
        let json = try await result(url(Self.api, "api/v3/h5/homeIndex"), label: "首页")
        var home = CopyHome()
        let definitions = [
            ("recComics", "为你推荐"), ("newComics", "新书入库"), ("hotComics", "热门更新"),
            ("rankDayComics", "今日热门"), ("finishComics", "完结佳作")
        ]
        home.sections = definitions.compactMap { key, title in
            let entries = unique(items(json[key]).compactMap(comic))
            return entries.isEmpty ? nil : CopySection(id: key, title: title, entries: entries)
        }
        home.features = items(json["banners"]).compactMap { banner in
            guard (banner["type"] as? Int) == 1, let raw = banner["comic"] as? [String: Any], let entry = comic(raw) else { return nil }
            let brief = (banner["brief"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? entry.manga.title
            return CopyFeature(title: brief, image: webUrl(banner["cover"] as? String), comic: entry)
        }
        let topics = items(json["topicsList"])
        home.topics = (topics.isEmpty ? items(json["topics"]) : topics).map(topic)
        let periods = [("day", "日榜", "rankDayComics"), ("week", "周榜", "rankWeekComics"), ("month", "月榜", "rankMonthComics")]
        home.rankings = periods.compactMap { period, title, key in
            let entries = unique(items(json[key]).compactMap(comic))
            return entries.isEmpty ? nil : CopyRanking(period: period, title: title, entries: entries)
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
                json = try await result(url(Self.website, "api/kb/web/searchcl/comics", [
                    ("q", keyword), ("q_type", ""), ("platform", "2"), ("limit", String(limit)), ("offset", offset)
                ]), label: "搜索")
            case "ranking":
                json = try await result(url(Self.api, "api/v3/ranks", [
                    ("limit", String(limit)), ("offset", offset), ("type", "1"),
                    ("audience_type", query.audience), ("date_type", query.rankPeriod)
                ]), label: "排行榜")
            case "topic":
                json = try await result(url(Self.api, "api/v3/topic/\(try pathKey(query.topic))/contents", [
                    ("limit", String(limit)), ("offset", offset)
                ]), label: "专题漫画")
            default:
                var parameters = [("limit", String(limit)), ("offset", offset), ("ordering", query.order)]
                if !query.theme.isEmpty { parameters.append(("theme", query.theme)) }
                if query.kind == "finished" { parameters.append(("top", "finish")) }
                json = try await result(url(Self.api, "api/v3/comics", parameters), label: "漫画目录")
        }
        let entries = unique(items(json).compactMap(comic))
        return CopyPage(entries: entries, more: hasMore(json, count: entries.count, page: page, limit: limit))
    }

    func topics(page: Int) async throws -> CopyPage<CopyTopic> {
        let limit = 20
        let json = try await result(url(Self.api, "api/v3/topics", [("limit", String(limit)), ("offset", String((page - 1) * limit))]), label: "专题")
        let entries = items(json).map(topic)
        return CopyPage(entries: entries, more: hasMore(json, count: entries.count, page: page, limit: limit))
    }

    func topicDetail(_ key: String) async throws -> CopyTopic {
        topic(try await result(url(Self.api, "api/v3/topic/\(try pathKey(key))"), label: "专题详情"))
    }

    // MARK: Details

    func details(key: String) async throws -> AidokuRunner.Manga {
        let json = try await result(url(Self.contentApi, "api/v3/comic2/\(try pathKey(key))", content: true), label: "漫画详情", content: true)
        guard let raw = json["comic"] as? [String: Any], let entry = comic(raw) else { throw CopyError.message("\(Self.name)没有返回漫画详情") }
        return entry.manga
    }

    func chapters(key: String) async throws -> [AidokuRunner.Chapter] {
        let json = try await result(url(Self.contentApi, "api/v3/comic2/\(try pathKey(key))", content: true), label: "漫画详情", content: true)
        var groups: [(String, String)] = []
        if let value = json["groups"] as? [String: Any] {
            groups = value.keys.sorted().map { groupKey in
                ((value[groupKey] as? [String: Any])?["name"] as? String ?? groupKey, groupKey)
            }
            // The default group lists the main chapters; keep it first like the website.
            groups.sort { $0.1 == "default" && $1.1 != "default" }
        }
        if groups.isEmpty { groups = [("", "default")] }
        var chapters: [(chapter: AidokuRunner.Chapter, date: Double, number: Double)] = []
        var seenKeys = Set<String>()
        for (groupName, groupKey) in groups {
            var offset = 0
            var seen = Set<String>()
            while true {
                let data = try await result(url(Self.contentApi, "api/v3/comic/\(try pathKey(key))/group/\(try pathKey(groupKey))/chapters", [
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
                    chapters.append((chapter, date, number ?? 0))
                }
                offset += raw.count
                let total = data["total"] as? Int ?? offset
                if raw.isEmpty || offset >= total || chapters.count >= 100_000 { break }
            }
        }
        return chapters.sorted { lhs, rhs in
            if lhs.date != rhs.date { return lhs.date > rhs.date }
            if lhs.number != rhs.number { return lhs.number > rhs.number }
            return lhs.chapter.key < rhs.chapter.key
        }.map { $0.chapter }
    }

    func pages(key: String, chapter: String) async throws -> [String] {
        let data = try await result(url(Self.contentApi, "api/v3/comic/\(try pathKey(key))/chapter/\(try pathKey(chapter))", content: true),
            label: "阅读图片", content: true)
        if (data["is_lock"] as? Bool) == true { throw CopyError.message("该章节需要拷贝账号的阅读权限") }
        guard let content = data["chapter"] as? [String: Any] else { throw CopyError.message("\(Self.name)未返回章节图片") }
        return try Self.orderedPages(content).map { webUrl($0) }
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

    private func items(_ value: Any?) -> [[String: Any]] {
        if let array = value as? [[String: Any]] { return array }
        if let object = value as? [String: Any] { return object["list"] as? [[String: Any]] ?? [] }
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
        return URL(string: address, relativeTo: Self.website)?.absoluteString ?? ""
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
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        request.setValue(Self.website.absoluteString, forHTTPHeaderField: "Referer")
        return request
    }
}
