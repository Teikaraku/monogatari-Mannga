//
//  SettingsAboutView.swift
//  Monogatari
//
//  Created by Skitty on 9/19/25.
//

import SwiftUI

struct SettingsAboutView: View {
    var body: some View {
        List {
            Section {
                HStack {
                    Text(NSLocalizedString("VERSION"))
                    Spacer()
                    let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
                    ?? NSLocalizedString("UNKNOWN")
                    Text(version)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                SettingView(setting: .init(
                    title: NSLocalizedString("GITHUB_REPO"),
                    value: .link(.init(url: "https://github.com/Teikaraku/monogatari-reader"))
                ))
            }
        }
        .navigationTitle(NSLocalizedString("ABOUT"))
    }
}
