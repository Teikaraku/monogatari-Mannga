//
//  SettingsSeriesView.swift
//  Monogatari
//

import SwiftUI

/// Another app or site of the 物语 family.
struct RelatedProject: Identifiable {
    let name: String
    let summaryKey: String
    let icon: String
    /// Nil for this app, which is shown without an action.
    var url: URL?
    var actionKey = "DOWNLOAD"

    var id: String { name }

    static let all: [RelatedProject] = [
        .init(name: "物语漫画", summaryKey: "PROJECT_MANGA_SUMMARY", icon: "ProjectManga"),
        .init(
            name: "物语番剧",
            summaryKey: "PROJECT_ANIME_SUMMARY",
            icon: "ProjectAnime",
            url: URL(string: "https://github.com/Teikaraku/monogatari-anime/releases/latest")
        ),
        .init(
            name: "物语轻小说",
            summaryKey: "PROJECT_NOVEL_SUMMARY",
            icon: "ProjectNovel",
            url: URL(string: "https://github.com/Teikaraku/monogatari-novel/releases/latest")
        ),
        .init(
            name: "物语GAL",
            summaryKey: "PROJECT_GAL_SUMMARY",
            icon: "ProjectGal",
            url: URL(string: "https://monogatari.top"),
            actionKey: "VISIT"
        )
    ]
}

struct SettingsSeriesView: View {
    var body: some View {
        List {
            Section {
                ForEach(RelatedProject.all) { project in
                    ProjectRow(project: project)
                }
            } header: {
                Text(NSLocalizedString("RELATED_PROJECTS"))
            }
        }
        .navigationTitle(NSLocalizedString("MONOGATARI_SERIES"))
    }
}

private struct ProjectRow: View {
    let project: RelatedProject

    private static let iconSize: CGFloat = 48

    var body: some View {
        HStack(spacing: 14) {
            Image(project.icon)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: Self.iconSize, height: Self.iconSize)
                .clipShape(RoundedRectangle(cornerRadius: Self.iconSize * 0.23, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(project.name)
                    .font(.body.weight(.medium))
                Text(NSLocalizedString(project.summaryKey))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            if let url = project.url {
                let action = NSLocalizedString(project.actionKey)
                Button(action) {
                    // Always leave the app: releases and the wiki belong in the system browser.
                    UIApplication.shared.open(url)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(.accentColor)
                .accessibilityLabel(action + project.name)
                .accessibilityAddTraits(.isLink)
            } else {
                Text(NSLocalizedString("CURRENT_APP"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
            }
        }
        .padding(.vertical, 6)
    }
}
