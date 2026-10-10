//
//  CopySource.swift
//  Monogatari
//
//  The built-in 拷贝漫画 source. The bookstore tab draws its own pages (BookstoreView); this runner
//  serves manga details, chapters, pages and searches so the shared manga and reader screens work.
//

import AidokuRunner
import Foundation

extension AidokuRunner.Source {
    /// One shared instance, registered with the source manager and used by the bookstore tab.
    static let copyManga: AidokuRunner.Source = .init(
        key: CopyClient.sourceKey,
        name: CopyClient.name,
        version: 1,
        languages: ["zh"],
        urls: [CopyClient.website],
        contentRating: .safe,
        config: .init(supportsTagSearch: true),
        staticListings: CopySourceRunner.listings,
        staticFilters: CopySourceRunner.filters,
        runner: CopySourceRunner()
    )
}

actor CopySourceRunner: Runner {
    private let client = CopyClient()

    let features: SourceFeatures = .init(
        providesListings: true,
        providesImageRequests: true,
        providesBaseUrl: true
    )

    static let listings: [AidokuRunner.Listing] = [
        .init(id: "catalog", name: "发现"),
        .init(id: "updates", name: "热门更新"),
        .init(id: "newest", name: "新书入库"),
        .init(id: "ranking:day", name: "日榜"),
        .init(id: "ranking:week", name: "周榜"),
        .init(id: "ranking:month", name: "月榜"),
        .init(id: "ranking:total", name: "总榜"),
        .init(id: "finished", name: "完结")
    ]

    static let filters: [AidokuRunner.Filter] = [
        .init(
            id: "theme",
            title: "题材",
            value: .select(.init(
                isGenre: true,
                options: ["全部"] + CopyClient.genres.map { $0.name },
                ids: [""] + CopyClient.genres.map { $0.id }
            ))
        ),
        .init(
            id: "order",
            title: "排序",
            value: .sort(canAscend: false, options: ["最近更新", "人气优先"], defaultValue: .init(index: 0, ascending: false))
        )
    ]

    func getSearchMangaList(query: String?, page: Int, filters: [AidokuRunner.FilterValue]) async throws -> AidokuRunner.MangaPageResult {
        var request = CopyQuery()
        let keyword = query?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !keyword.isEmpty {
            request.kind = "search"
            request.keyword = keyword
        }
        for filter in filters {
            switch filter {
                case let .select(id, value) where id == "theme":
                    // Tag searches pass the genre name; picker selections pass its id.
                    request.theme = CopyClient.genres.first { $0.name == value || $0.id == value }?.id ?? ""
                case let .sort(value) where value.id == "order":
                    request.order = value.index == 1 ? "-popular" : "-datetime_updated"
                default:
                    break
            }
        }
        let result = try await client.catalog(request, page: page)
        return .init(entries: result.entries.map { $0.manga }, hasNextPage: result.more)
    }

    func getMangaList(listing: AidokuRunner.Listing, page: Int) async throws -> AidokuRunner.MangaPageResult {
        var request = CopyQuery()
        switch listing.id {
            case "updates":
                request.order = "-datetime_updated"
            case "newest":
                request.order = "-datetime_created"
            case "finished":
                request.kind = "finished"
            case let id where id.hasPrefix("ranking:"):
                request.kind = "ranking"
                request.rankPeriod = String(id.dropFirst("ranking:".count))
            case let id where id.hasPrefix("topic:"):
                request.kind = "topic"
                request.topic = String(id.dropFirst("topic:".count))
            default:
                break
        }
        let result = try await client.catalog(request, page: page)
        return .init(entries: result.entries.map { $0.manga }, hasNextPage: result.more)
    }

    func getMangaUpdate(manga: AidokuRunner.Manga, needsDetails: Bool, needsChapters: Bool) async throws -> AidokuRunner.Manga {
        var manga = manga
        if needsDetails {
            let details = try await client.details(key: manga.key)
            manga = manga.copy(from: details)
        }
        if needsChapters {
            manga.chapters = try await client.chapters(key: manga.key)
        }
        return manga
    }

    func getPageList(manga: AidokuRunner.Manga, chapter: AidokuRunner.Chapter) async throws -> [AidokuRunner.Page] {
        try await client.pages(key: manga.key, chapter: chapter.key).compactMap { address in
            URL(string: address).map { AidokuRunner.Page(content: .url(url: $0)) }
        }
    }

    func getImageRequest(url: String, context _: AidokuRunner.PageContext?) async throws -> URLRequest {
        guard let url = URL(string: url) else { throw CopyError.message("无效漫画资源地址") }
        return client.imageRequest(url)
    }

    func getBaseUrl() async throws -> URL? {
        CopyClient.website
    }
}
