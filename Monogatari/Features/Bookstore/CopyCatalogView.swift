//
//  CopyCatalogView.swift
//  Monogatari
//
//  Catalog pages behind the bookstore: discover, categories, rankings, completed, topics,
//  new arrivals, latest updates and search. Mirrors NativeCopyCatalogScreen on Android.
//

import AidokuRunner
import SwiftUI

@MainActor
final class CopyCatalogModel: ObservableObject {
    let mode: String
    let topicKey: String

    @Published var entries: [CopyComic] = []
    @Published var topics: [CopyTopic] = []
    @Published var topic: CopyTopic?
    @Published var draft = ""
    @Published var submitted = ""
    @Published var theme = ""
    @Published var order = "-datetime_updated"
    @Published var period = "day"
    @Published var audience = "male"
    @Published var more = false
    @Published var busy = false
    @Published var error: String?

    private(set) var initialized = false
    private(set) var lastAppend = false
    private var page = 0
    private var task: Task<Void, Never>?
    private let client = CopyClient()

    init(mode: String, topicKey: String) {
        self.mode = mode
        self.topicKey = topicKey
        // The home chart links to the period it is showing.
        if mode == "ranking" && ["day", "week", "month", "total"].contains(topicKey) {
            period = topicKey
        }
    }

    private var query: CopyQuery {
        var query = CopyQuery()
        if mode == "ranking" {
            query.kind = "ranking"
        } else if mode == "finished" {
            query.kind = "finished"
        } else if mode == "search" || !submitted.isEmpty {
            query.kind = "search"
        }
        query.keyword = submitted
        query.theme = theme
        query.order = mode == "newest" ? "-datetime_created" : order
        query.rankPeriod = period
        query.audience = audience
        return query
    }

    func load(append: Bool = false) {
        if append && busy { return }
        let captured = query
        let next = append ? page + 1 : 1
        lastAppend = append
        if !append {
            task?.cancel()
            entries = []
            topics = []
            topic = nil
            page = 0
            more = false
            initialized = false
        }
        busy = true
        error = nil
        task = Task {
            do {
                switch mode {
                    case "topics":
                        let result = try await client.topics(page: next)
                        guard !Task.isCancelled else { return }
                        var seen = Set<String>()
                        topics = ((append ? topics : []) + result.entries).filter { seen.insert($0.key).inserted }
                        more = result.more
                    case "topic":
                        let detail: CopyTopic
                        if let topic {
                            detail = topic
                        } else {
                            detail = try await client.topicDetail(topicKey)
                        }
                        var topicQuery = CopyQuery()
                        topicQuery.kind = "topic"
                        topicQuery.topic = topicKey
                        let result = try await client.catalog(topicQuery, page: next)
                        guard !Task.isCancelled else { return }
                        topic = detail
                        entries = merged(append ? entries : [], result.entries)
                        more = result.more
                    default:
                        let result = try await client.catalog(captured, page: next)
                        guard !Task.isCancelled else { return }
                        entries = merged(append ? entries : [], result.entries)
                        more = result.more
                }
                page = next
                initialized = true
            } catch {
                if !Task.isCancelled { self.error = error.localizedDescription }
            }
            if !Task.isCancelled { busy = false }
        }
    }

    /// Continues the list on its own; a page that failed waits for the user to retry.
    func loadMoreIfNeeded() {
        if initialized && more && !busy && error == nil { load(append: true) }
    }

    private func merged(_ old: [CopyComic], _ new: [CopyComic]) -> [CopyComic] {
        var seen = Set<String>()
        return (old + new).filter { seen.insert($0.id).inserted }
    }
}

struct CopyCatalogView: View {
    @StateObject private var model: CopyCatalogModel
    @EnvironmentObject private var path: NavigationCoordinator
    @State private var showGenres = false
    @State private var genresShownOnce = false

    init(mode: String, topicKey: String = "") {
        _model = StateObject(wrappedValue: CopyCatalogModel(mode: mode, topicKey: topicKey))
    }

    static func title(for mode: String) -> String {
        switch mode {
            case "categories": "分类"
            case "ranking": "排行榜"
            case "finished": "完结漫画"
            case "topics": "专题"
            case "topic": "专题详情"
            case "newest": "新书入库"
            case "updates": "热门更新"
            case "search": NSLocalizedString("SEARCH")
            default: "发现"
        }
    }

    private var showsSearch: Bool { !["topic", "topics", "ranking", "finished"].contains(model.mode) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if showsSearch { searchField }
                filterBar
                // Progress and failures of a next page show at the end of the list, where the reader is.
                if model.busy && !model.lastAppend {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                if let error = model.error, !model.lastAppend {
                    CopyErrorView(message: error) { model.load() }
                }
                content
                if let error = model.error, model.lastAppend {
                    CopyErrorView(message: error) { model.load(append: true) }
                } else if model.busy && model.lastAppend {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 14)
                } else if model.more {
                    Button("加载更多") { model.load(append: true) }
                        .disabled(model.busy)
                        .foregroundColor(bookstorePink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        // The lazy stack creates this row as the end of the list comes near.
                        .onAppear { model.loadMoreIfNeeded() }
                }
            }
            .padding(.bottom, 24)
        }
        .onAppear {
            if !model.initialized && !model.busy && model.mode != "search" { model.load() }
            if model.mode == "categories" && !genresShownOnce {
                genresShownOnce = true
                // Wait for the push transition before presenting the sheet.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showGenres = true }
            }
        }
        .sheet(isPresented: $showGenres) {
            CopyGenreSheet(selected: model.theme) { theme in
                model.theme = theme
                model.submitted = ""
                model.draft = ""
                model.load()
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(.secondary)
            TextField(NSLocalizedString("SEARCH"), text: $model.draft)
                .submitLabel(.search)
                .onSubmit {
                    model.submitted = model.draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.load()
                }
            if !model.draft.isEmpty {
                Button {
                    model.draft = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private var filterBar: some View {
        if model.mode == "ranking" {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([("day", "日榜"), ("week", "周榜"), ("month", "月榜"), ("total", "总榜")], id: \.0) { value, label in
                        CopyChip(title: label, selected: model.period == value, checkmark: false) {
                            model.period = value
                            model.load()
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 6)
            HStack(spacing: 8) {
                ForEach([("male", "少年向"), ("female", "少女向")], id: \.0) { value, label in
                    CopyChip(title: label, selected: model.audience == value, checkmark: false) {
                        model.audience = value
                        model.load()
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 6)
        } else if !["topic", "topics", "search"].contains(model.mode) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    CopyChip(
                        title: CopyClient.genres.first { $0.id == model.theme }?.name ?? "全部分类",
                        selected: !model.theme.isEmpty,
                        checkmark: false,
                        trailingIcon: "chevron.down"
                    ) {
                        showGenres = true
                    }
                    if model.mode != "newest" {
                        ForEach([("-datetime_updated", "最近更新"), ("-popular", "人气优先")], id: \.0) { value, label in
                            CopyChip(title: label, selected: model.order == value, checkmark: false) {
                                model.order = value
                                model.load()
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 6)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.mode {
            case "topics":
                // One column on iPhone; iPads fit two or more topics per row.
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 360), spacing: 0)], alignment: .leading, spacing: 0) {
                    ForEach(model.topics) { topic in
                        Button {
                            path.push(CopyCatalogView(mode: "topic", topicKey: topic.key), title: topic.title)
                        } label: {
                            CopyTopicRow(topic: topic)
                        }
                        .buttonStyle(.plain)
                    }
                }
            case "topic":
                if let topic = model.topic {
                    VStack(alignment: .leading, spacing: 12) {
                        Color(uiColor: .secondarySystemBackground)
                            .aspectRatio(2.5, contentMode: .fit)
                            .overlay(SourceImageView(source: .copyManga, imageUrl: topic.fullImage, placeholder: "BannerPlaceholder"))
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                        if !topic.subtitle.isEmpty {
                            Text(topic.subtitle).foregroundColor(.secondary)
                        }
                        if !topic.intro.isEmpty {
                            Text(Self.plainText(topic.intro)).lineSpacing(6).textSelection(.enabled)
                        }
                    }
                    .frame(maxWidth: 752, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
                grid(showLatest: false)
            case "ranking":
                emptyMessage
                // A single ranked list on iPhone; iPads read the chart in two or more columns.
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 340), spacing: 16)], alignment: .leading, spacing: 8) {
                    ForEach(Array(model.entries.enumerated()), id: \.element.id) { index, comic in
                        CopyRankRow(comic: comic, position: index + 1)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
            default:
                emptyMessage
                grid(showLatest: model.mode == "updates" || model.mode == "newest")
        }
    }

    @ViewBuilder
    private var emptyMessage: some View {
        if !model.busy && model.error == nil && model.entries.isEmpty {
            Text(model.mode == "search" && model.submitted.isEmpty ? "输入漫画名或作者开始搜索" : "没有找到符合条件的漫画")
                .foregroundColor(.secondary)
                .padding(20)
        }
    }

    private func grid(showLatest: Bool) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12, alignment: .top)], alignment: .leading, spacing: 18) {
            ForEach(model.entries) { comic in
                CopyBookCard(comic: comic, showLatest: showLatest)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private static func plainText(_ html: String) -> String {
        html.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct CopyTopicRow: View {
    let topic: CopyTopic

    var body: some View {
        HStack(spacing: 12) {
            Color(uiColor: .secondarySystemBackground)
                .frame(width: 150, height: 60)
                .overlay(SourceImageView(source: .copyManga, imageUrl: topic.fullImage, placeholder: "BannerPlaceholder"))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 4) {
                Text(topic.title).font(.system(size: 15, weight: .medium)).lineLimit(2).multilineTextAlignment(.leading)
                if !topic.subtitle.isEmpty {
                    Text(topic.subtitle).font(.system(size: 11)).foregroundColor(.secondary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(.secondary)
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/// Every genre is visible at once; Comic Market event tags are grouped apart from subject genres.
struct CopyGenreSheet: View {
    let selected: String
    let onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private static let eventPattern = #"^C\d+$"#

    var body: some View {
        let subjects = CopyClient.genres.filter { $0.name.range(of: Self.eventPattern, options: .regularExpression) == nil }
        let events = CopyClient.genres
            .filter { $0.name.range(of: Self.eventPattern, options: .regularExpression) != nil }
            .sorted { (Int($0.name.dropFirst()) ?? 0) > (Int($1.name.dropFirst()) ?? 0) }
        VStack(spacing: 0) {
            HStack {
                Text("漫画分类").font(.system(size: 19, weight: .semibold))
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.secondary)
                        .frame(width: 30, height: 30)
                        .background(Color(uiColor: .secondarySystemBackground), in: Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 4)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    section("题材", [("", "全部")] + subjects)
                    if !events.isEmpty {
                        section("同人展", events)
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .copySheetDetents()
    }

    private func section(_ title: String, _ genres: [(id: String, name: String)]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 8)], alignment: .leading, spacing: 8) {
                ForEach(genres, id: \.id) { genre in
                    CopyChip(title: genre.name, selected: genre.id == selected) {
                        onSelect(genre.id)
                        dismiss()
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
    }
}

extension View {
    @ViewBuilder
    func copySheetDetents() -> some View {
        if #available(iOS 16.0, *) {
            presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        } else {
            self
        }
    }
}
