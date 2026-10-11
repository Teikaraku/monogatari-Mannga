//
//  SettingsAboutView.swift
//  Monogatari
//
//  Created by Skitty on 9/19/25.
//

import SwiftUI

struct SettingsAboutView: View {
    @State private var checking = false
    @State private var result: String?

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
                Button {
                    checkForUpdate()
                } label: {
                    HStack {
                        Text("检查更新").foregroundColor(.primary)
                        Spacer()
                        if checking { ProgressView() }
                    }
                }
                .disabled(checking)
            }

            Section {
                SettingView(setting: .init(
                    title: NSLocalizedString("GITHUB_REPO"),
                    value: .link(.init(url: "https://github.com/Teikaraku/monogatari-reader"))
                ))
            }
        }
        .navigationTitle(NSLocalizedString("ABOUT"))
        .alert(Text(result ?? ""), isPresented: Binding(get: { result != nil }, set: { if !$0 { result = nil } })) {
            Button(NSLocalizedString("OK")) {}
        }
    }

    private func checkForUpdate() {
        checking = true
        Task { @MainActor in
            AppUpdatePresenter.start()
            switch await AppUpdateManager.shared.check(manual: true) {
                case .available: AppUpdatePresenter.present()
                case .current: result = "已是最新版本"
                case .failed: result = "检查更新失败，请稍后重试。"
            }
            checking = false
        }
    }
}
