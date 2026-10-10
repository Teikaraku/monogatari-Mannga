//
//  BookstoreView.swift
//  Monogatari
//
//  The bookstore tab. Layout and behaviour follow the Android bookstore (BookstoreScreens.kt):
//  three picks fill one grid row, rankings read as a numbered chart with day/week/month tabs,
//  the long update feed scrolls in two rows, and the short completed list stays a fixed grid.
//

import AidokuRunner
import Combine
import SwiftUI

let bookstorePink = Color(uiColor: .systemPink)

@MainActor
final class BookstoreViewModel: ObservableObject {
    @Published var home: CopyHome?
    @Published var busy = false
    @Published var error: String?

    private let client = CopyClient()
    private var task: Task<Void, Never>?

    func refresh() {
        task?.cancel()
        busy = true
        error = nil
        task = Task {
            do {
                let result = try await client.home()
                if !Task.isCancelled { home = result }
            } catch {
                if !Task.isCancelled { self.error = error.localizedDescription }
            }
            if !Task.isCancelled { busy = false }
        }
    }

    func refreshAndWait() async {
        refresh()
        await task?.value
    }
}

final class BookstoreViewController: UIHostingController<AnyView> {
    private let model: BookstoreViewModel
    private let coordinator: NavigationCoordinator

    init() {
        let model = BookstoreViewModel()
        let coordinator = NavigationCoordinator(rootViewController: nil)
        self.model = model
        self.coordinator = coordinator
        super.init(rootView: AnyView(BookstoreView(model: model).environmentObject(coordinator)))
        coordinator.rootViewController = self
        title = CopyClient.name
        navigationItem.largeTitleDisplayMode = .always
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(title: "图源管理", image: UIImage(systemName: "slider.horizontal.3"), primaryAction: UIAction { [weak self] _ in
                self?.navigationController?.pushViewController(BrowseViewController(), animated: true)
            }),
            UIBarButtonItem(title: "刷新", image: UIImage(systemName: "arrow.clockwise"), primaryAction: UIAction { [weak self] _ in
                self?.model.refresh()
            }),
            UIBarButtonItem(title: NSLocalizedString("SEARCH"), image: UIImage(systemName: "magnifyingglass"), primaryAction: UIAction { [weak self] _ in
                guard let self else { return }
                self.coordinator.push(CopyCatalogView(mode: "search"), title: NSLocalizedString("SEARCH"))
            })
        ]
    }

    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

struct BookstoreView: View {
    @ObservedObject var model: BookstoreViewModel
    @EnvironmentObject private var path: NavigationCoordinator

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let features = model.home?.features, !features.isEmpty {
                    CopyFeaturePager(features: features)
                } else if model.home == nil && model.error == nil {
                    RoundedRectangle(cornerRadius: 18).fill(Color(uiColor: .secondarySystemBackground))
                        .frame(height: 190).padding(.horizontal, 20)
                }
                shortcuts
                if let error = model.error {
                    CopyErrorView(message: error) { model.refresh() }
                }
                if let home = model.home {
                    if let notice = home.notice {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(notice).font(.system(size: 13)).foregroundColor(.secondary)
                            if let details = home.diagnostics {
                                DisclosureGroup("查看加载详情") {
                                    Text(details).font(.system(size: 12)).textSelection(.enabled)
                                }
                                .font(.system(size: 13))
                            }
                        }
                        .padding(16)
                    }
                    shelves(home)
                } else if model.error == nil {
                    CopyShelfPlaceholder()
                }
            }
            .padding(.bottom, 28)
        }
        .refreshable { await model.refreshAndWait() }
        .onAppear {
            if model.home == nil && !model.busy { model.refresh() }
        }
    }

    private var shortcuts: some View {
        HStack(spacing: 0) {
            shortcut("发现", "safari", "discover")
            shortcut("分类", "square.grid.2x2", "categories")
            shortcut("排行", "chart.bar.fill", "ranking")
            shortcut("完结", "checkmark.seal", "finished")
            shortcut("专题", "rectangle.stack", "topics")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
    }

    private func shortcut(_ title: String, _ icon: String, _ mode: String) -> some View {
        Button {
            openCatalog(mode)
        } label: {
            VStack(spacing: 7) {
                Image(systemName: icon)
                    .font(.system(size: 21, weight: .medium))
                    .foregroundColor(bookstorePink)
                    .frame(width: 44, height: 44)
                    .background(bookstorePink.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                Text(title).font(.system(size: 12)).foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func shelves(_ home: CopyHome) -> some View {
        let sections = Dictionary(home.sections.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        if let section = sections["recComics"] {
            CopyShelfHeader(title: section.title)
            CopyBookGrid(entries: Array(section.entries.prefix(6)))
        }
        if !home.rankings.isEmpty {
            CopyRankingShelf(rankings: home.rankings)
        } else if let section = sections["rankDayComics"] {
            CopyShelfHeader(title: section.title) { openCatalog("ranking") }
            CopyBookRow(entries: section.entries)
        }
        if let section = sections["hotComics"] {
            CopyShelfHeader(title: section.title) { openCatalog("updates") }
            CopyTwoRowShelf(entries: section.entries)
        }
        if let section = sections["newComics"] {
            CopyShelfHeader(title: section.title) { openCatalog("newest") }
            CopyBookRow(entries: section.entries)
        }
        if let section = sections["finishComics"] {
            CopyShelfHeader(title: section.title) { openCatalog("finished") }
            CopyBookGrid(entries: Array(section.entries.prefix(6)))
        }
        if !home.topics.isEmpty {
            CopyShelfHeader(title: "精选专题") { openCatalog("topics") }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(home.topics) { topic in
                        Button {
                            path.push(CopyCatalogView(mode: "topic", topicKey: topic.key), title: topic.title)
                        } label: {
                            CopyTopicBanner(topic: topic).frame(width: 264)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private func openCatalog(_ mode: String) {
        path.push(CopyCatalogView(mode: mode), title: CopyCatalogView.title(for: mode))
    }
}

// MARK: - Shared pieces

struct CopyShelfHeader: View {
    let title: String
    var onMore: (() -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(bookstorePink).frame(width: 4, height: 19)
            Text(title).font(.system(size: 20, weight: .bold))
            Spacer()
            if let onMore {
                Button(action: onMore) {
                    HStack(spacing: 2) {
                        Text("更多").font(.system(size: 14))
                        Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundColor(bookstorePink)
                }
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 16)
        .padding(.top, 22)
        .padding(.bottom, 12)
    }
}

struct CopyFeaturePager: View {
    let features: [CopyFeature]
    @EnvironmentObject private var path: NavigationCoordinator
    @State private var page = 0
    private let timer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 10) {
            TabView(selection: $page) {
                ForEach(Array(features.enumerated()), id: \.offset) { index, feature in
                    Button {
                        path.push(MangaViewController(source: .copyManga, manga: feature.comic.manga, parent: path.rootViewController))
                    } label: {
                        ZStack(alignment: .bottomLeading) {
                            Color(uiColor: .secondarySystemBackground)
                                .overlay(SourceImageView(source: .copyManga, imageUrl: feature.image, placeholder: "BannerPlaceholder"))
                                .clipped()
                            LinearGradient(colors: [.clear, .black.opacity(0.68)], startPoint: .top, endPoint: .bottom)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(feature.comic.manga.title).font(.system(size: 19, weight: .bold)).lineLimit(1)
                                Text(feature.title).font(.system(size: 12)).opacity(0.9).lineLimit(2)
                            }
                            .foregroundColor(.white)
                            .padding(16)
                        }
                        .frame(height: 190)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                        .padding(.horizontal, 20)
                    }
                    .buttonStyle(.plain)
                    .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: 190)
            HStack(spacing: 6) {
                ForEach(features.indices, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? bookstorePink : Color(uiColor: .systemGray4))
                        .frame(width: index == page ? 16 : 6, height: 6)
                }
            }
            .frame(maxWidth: .infinity)
            .animation(.easeInOut(duration: 0.2), value: page)
        }
        .onReceive(timer) { _ in
            guard features.count > 1 else { return }
            withAnimation { page = (page + 1) % features.count }
        }
    }
}

/// Cover, title and a secondary line; update feeds overlay the newest chapter on the cover.
struct CopyBookCard: View {
    let comic: CopyComic
    var titleLines = 2
    var showLatest = false
    @EnvironmentObject private var path: NavigationCoordinator

    var body: some View {
        Button {
            path.push(MangaViewController(source: .copyManga, manga: comic.manga, parent: path.rootViewController))
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                Color(uiColor: .secondarySystemBackground)
                    .aspectRatio(0.72, contentMode: .fit)
                    .overlay(SourceImageView(source: .copyManga, imageUrl: comic.manga.cover ?? ""))
                    .overlay(alignment: .bottomLeading) {
                        if showLatest, let latest = comic.latestChapter {
                            Text(latest)
                                .font(.system(size: 11))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .padding(.horizontal, 7)
                                .padding(.top, 14)
                                .padding(.bottom, 5)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(LinearGradient(colors: [.clear, .black.opacity(0.72)], startPoint: .top, endPoint: .bottom))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Text(comic.manga.title)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(titleLines)
                    .multilineTextAlignment(.leading)
                    .padding(.top, 7)
                if let author = comic.manga.authors?.joined(separator: "、"), !author.isEmpty {
                    Text(author).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
                }
            }
            .foregroundColor(.primary)
        }
        .buttonStyle(.plain)
    }
}

struct CopyBookGrid: View {
    let entries: [CopyComic]
    var columns = 3

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: columns), alignment: .leading, spacing: 16) {
            ForEach(entries) { comic in
                CopyBookCard(comic: comic)
            }
        }
        .padding(.horizontal, 20)
    }
}

struct CopyBookRow: View {
    let entries: [CopyComic]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(entries) { comic in
                    CopyBookCard(comic: comic, showLatest: true).frame(width: 108)
                }
            }
            .padding(.horizontal, 20)
        }
    }
}

/// Two rows that scroll together, so the twelve-item update feed takes two screens instead of four.
struct CopyTwoRowShelf: View {
    let entries: [CopyComic]

    var body: some View {
        let rows = entries.count > 3 ? 2 : 1
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHGrid(rows: Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: rows), alignment: .top, spacing: 12) {
                ForEach(entries) { comic in
                    CopyBookCard(comic: comic, titleLines: 1, showLatest: true).frame(width: 104)
                }
            }
            .padding(.horizontal, 20)
        }
    }
}

struct CopyRankingShelf: View {
    let rankings: [CopyRanking]
    @EnvironmentObject private var path: NavigationCoordinator
    @State private var period = "day"

    var body: some View {
        let current = rankings.first { $0.period == period } ?? rankings[0]
        let rows = min(max(current.entries.count, 1), 3)
        VStack(alignment: .leading, spacing: 0) {
            CopyShelfHeader(title: "排行榜") {
                path.push(CopyCatalogView(mode: "ranking", topicKey: current.period), title: CopyCatalogView.title(for: "ranking"))
            }
            HStack(spacing: 8) {
                ForEach(rankings) { ranking in
                    CopyChip(title: ranking.title, selected: ranking.period == current.period, checkmark: false) { period = ranking.period }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 10)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHGrid(rows: Array(repeating: GridItem(.fixed(78), spacing: 8), count: rows), spacing: 16) {
                    ForEach(Array(current.entries.enumerated()), id: \.element.id) { index, comic in
                        CopyRankRow(comic: comic, position: index + 1)
                            .frame(width: UIScreen.main.bounds.width * 0.8)
                    }
                }
                .padding(.horizontal, 20)
            }
            .id(current.period)
        }
    }
}

struct CopyRankRow: View {
    let comic: CopyComic
    let position: Int
    @EnvironmentObject private var path: NavigationCoordinator

    var body: some View {
        let rank = (comic.rank ?? 0) > 0 ? comic.rank ?? position : position
        Button {
            path.push(MangaViewController(source: .copyManga, manga: comic.manga, parent: path.rootViewController))
        } label: {
            HStack(spacing: 0) {
                Text("\(rank)")
                    .font(.system(size: rank <= 3 ? 24 : 18, weight: .heavy).italic())
                    .foregroundColor(rankColor(rank))
                    .frame(width: 34)
                Color(uiColor: .secondarySystemBackground)
                    .frame(width: 54, height: 72)
                    .overlay(SourceImageView(source: .copyManga, imageUrl: comic.manga.cover ?? ""))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 2) {
                    Text(comic.manga.title).font(.system(size: 15, weight: .medium)).lineLimit(1)
                    if let author = comic.manga.authors?.joined(separator: "、"), !author.isEmpty {
                        Text(author).font(.system(size: 12)).foregroundColor(.secondary).lineLimit(1)
                    }
                    if let heat = comic.heat, heat > 0 {
                        HStack(spacing: 2) {
                            Image(systemName: "flame").font(.system(size: 11)).foregroundColor(bookstorePink.opacity(0.8))
                            Text(Self.heatText(heat)).font(.system(size: 11)).foregroundColor(.secondary)
                        }
                    }
                }
                .padding(.leading, 12)
                Spacer(minLength: 4)
                if let previous = comic.previousRank, previous > 0, previous != rank {
                    Image(systemName: rank < previous ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
                        .font(.system(size: 10))
                        .foregroundColor(rank < previous ? bookstorePink : .secondary)
                }
            }
            .frame(height: 78)
            .foregroundColor(.primary)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func rankColor(_ rank: Int) -> Color {
        switch rank {
            case 1: bookstorePink
            case 2: Color(red: 1, green: 0.42, blue: 0.24)
            case 3: Color(red: 1, green: 0.63, blue: 0.14)
            default: .secondary
        }
    }

    static func heatText(_ value: Int) -> String {
        if value >= 100_000_000 { return String(format: "%.1f亿", Double(value) / 1e8) }
        if value >= 10_000 { return String(format: "%.1f万", Double(value) / 1e4) }
        return String(value)
    }
}

struct CopyTopicBanner: View {
    let topic: CopyTopic

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color(uiColor: .secondarySystemBackground)
                .aspectRatio(2.5, contentMode: .fit)
                .overlay(SourceImageView(source: .copyManga, imageUrl: topic.fullImage, placeholder: "BannerPlaceholder"))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            Text(topic.title)
                .font(.system(size: 14, weight: .medium))
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .padding(.top, 8)
            if !topic.subtitle.isEmpty {
                Text(topic.subtitle).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
            }
        }
        .foregroundColor(.primary)
    }
}

/// Selected chips use the accent tint; excluded or unselected chips use the grouped fill, as on Android.
struct CopyChip: View {
    let title: String
    let selected: Bool
    var checkmark = true
    var trailingIcon: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected && checkmark && trailingIcon == nil {
                    Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 14, weight: selected ? .medium : .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                if let trailingIcon {
                    Image(systemName: trailingIcon).font(.system(size: 11, weight: .semibold))
                }
            }
            .foregroundColor(selected ? bookstorePink : .primary)
            .padding(.horizontal, 14)
            .frame(minWidth: 56, minHeight: 36)
            .background(
                Capsule().fill(selected ? bookstorePink.opacity(0.1) : Color(uiColor: .secondarySystemBackground))
            )
            .overlay(Capsule().strokeBorder(selected ? bookstorePink.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct CopyErrorView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(message.components(separatedBy: "\n").first ?? message)
                .font(.system(size: 13)).foregroundColor(.red)
            if let separator = message.firstIndex(of: "\n") {
                DisclosureGroup("查看加载详情") {
                    Text(String(message[message.index(after: separator)...]))
                        .font(.system(size: 12)).textSelection(.enabled)
                }
                .font(.system(size: 13))
            }
            Button("重试", action: retry).foregroundColor(bookstorePink)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .padding(16)
    }
}

struct CopyShelfPlaceholder: View {
    var body: some View {
        let tone = Color(uiColor: .secondarySystemBackground)
        VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<2, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 6).fill(tone).frame(width: 96, height: 20).padding(.top, 18).padding(.bottom, 14)
                HStack(alignment: .top, spacing: 12) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 8).fill(tone).aspectRatio(0.72, contentMode: .fit)
                            RoundedRectangle(cornerRadius: 4).fill(tone).frame(height: 12)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 20)
    }
}
