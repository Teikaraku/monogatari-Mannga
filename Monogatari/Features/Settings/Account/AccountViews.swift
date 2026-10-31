//
//  AccountViews.swift
//  Monogatari
//
//  The settings header for the 物语 account and the account pages: profile, security, devices, sync,
//  sign-out and deleting this app's data. Mirrors android/.../ui/AccountScreens.kt.
//

import CoreData
import SwiftUI

private func relative(_ date: Date, synced: Bool) -> String {
    let minutes = Int(Date().timeIntervalSince(date) / 60)
    let prefix = synced ? "MO_SYNCED" : "MO_ACTIVE"
    if minutes < 1 { return NSLocalizedString("\(prefix)_JUST_NOW") }
    if minutes < 60 { return String(format: NSLocalizedString("\(prefix)_MINUTES_%i"), minutes) }
    if minutes < 24 * 60 { return String(format: NSLocalizedString("\(prefix)_HOURS_%i"), minutes / 60) }
    return String(format: NSLocalizedString("\(prefix)_ON_%@"), shortDate(date))
}

/// 2026/10/31, as on Android.
private func shortDate(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy/M/d"
    return formatter.string(from: date)
}

@MainActor private func syncLine(_ sync: AccountSync) -> String {
    switch sync.phase {
        case .syncing: return NSLocalizedString("MO_SYNCING")
        case .failed:
            switch sync.error {
                case "quota_exceeded": return NSLocalizedString("MO_SYNC_QUOTA")
                case "unauthorized", "device_revoked": return NSLocalizedString("MO_SYNC_SIGNED_OUT")
                case "network": return NSLocalizedString("MO_NETWORK_ERROR")
                default: return NSLocalizedString("MO_SYNC_FAILED")
            }
        case .idle: return sync.lastSynced.map { relative($0, synced: true) } ?? NSLocalizedString("MO_SYNC_NEVER")
    }
}

/// The first row of the settings: who is signed in and how sync is doing, or sign-in.
struct AccountHeaderRow: View {
    @ObservedObject private var sync = AccountSync.shared
    @State private var signingIn = false

    var body: some View {
        Section {
            if let user = sync.user {
                NavigationLink { AccountView() } label: { label(user) }
            } else {
                Button { signingIn = true } label: { label(nil) }.foregroundStyle(.primary)
            }
        }
        .sheet(isPresented: $signingIn) { AuthFlowView() }
        .alert(Text(sync.notice ?? ""), isPresented: Binding(get: { sync.notice != nil && !signingIn }, set: { if !$0 { sync.notice = nil } })) {
            Button(NSLocalizedString("OK")) {}
        }
    }

    private func label(_ user: AccountUser?) -> some View {
        HStack(spacing: 12) {
            AccountAvatar(name: user?.displayName, url: user?.picture, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(user?.displayName ?? NSLocalizedString("MO_SIGN_IN_OR_REGISTER")).font(.headline).lineLimit(1)
                if user != nil { Text(syncLine(sync)).font(.subheadline).foregroundStyle(.secondary) }
            }
            Spacer()
            if user == nil { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary) }
        }
        .padding(.vertical, 4)
    }
}

/// This app's data at a glance.
private struct LibraryStats {
    var titles = 0, chapters = 0, days = 0

    static func load() async -> LibraryStats {
        await CoreDataManager.shared.container.performBackgroundTask { context in
            var stats = LibraryStats()
            stats.titles = CoreDataManager.shared.getLibraryManga(context: context).count
            let history = CoreDataManager.shared.getHistory(context: context)
            stats.chapters = history.filter(\.completed).count
            let calendar = Calendar.current
            stats.days = Set(history.compactMap { $0.dateRead }.filter { $0 > .distantPast }.map { calendar.startOfDay(for: $0) }).count
            return stats
        }
    }
}

/// Which sensitive change the identity check is for.
enum SensitiveChange: Identifiable { case email, password, unlink(String)
    var id: String { switch self { case .email: "email"; case .password: "password"; case .unlink(let p): "unlink-\(p)" } }
}

struct AccountView: View {
    @ObservedObject private var sync = AccountSync.shared
    @Environment(\.dismiss) private var dismiss
    @State private var profile: AccountProfile?
    @State private var loadError: String?
    @State private var stats = LibraryStats()
    @State private var uploading = false
    @State private var avatarChoice = false
    @State private var picking = false
    @State private var picked: UIImage?
    @State private var confirmSignOut = false
    @State private var message: String?
    @State private var verifying: SensitiveChange?
    @State private var verified: (SensitiveChange, String)?

    var body: some View {
        let user = sync.user
        List {
            Section {
                VStack(spacing: 6) {
                    Button { if profile != nil { avatarChoice = true } } label: {
                        ZStack(alignment: .bottomTrailing) {
                            AccountAvatar(name: profile?.name ?? user?.displayName, url: profile?.avatar ?? user?.picture, size: 84)
                                .overlay { if uploading { Circle().fill(.black.opacity(0.38)); ProgressView().tint(.white) } }
                            Image(systemName: "camera.fill").font(.caption2).foregroundStyle(.secondary)
                                .padding(6).background(Circle().fill(Color(.secondarySystemGroupedBackground)))
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(NSLocalizedString("MO_AVATAR"))
                    Text(profile?.name ?? user?.displayName ?? "").font(.title2.bold())
                    if let email = profile?.email ?? user?.email { Text(email).font(.subheadline).foregroundStyle(.secondary) }
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                HStack {
                    stat(stats.titles, "MO_STAT_LIBRARY")
                    stat(stats.chapters, "MO_STAT_CHAPTERS")
                    stat(stats.days, "MO_STAT_DAYS")
                }
            }
            if let loadError {
                Section { Button { Task { await load() } } label: { Label(loadError, systemImage: "wifi.slash") } }
            }
            Section(NSLocalizedString("MO_PROFILE")) {
                NavigationLink { NicknameView(initial: profile?.name ?? user?.displayName ?? "") { profile = $0 } } label: {
                    LabeledRow(title: NSLocalizedString("MO_NICKNAME"), value: profile?.name ?? user?.displayName ?? "")
                }
                .disabled(profile == nil)
                Button { avatarChoice = true } label: {
                    HStack {
                        Text(NSLocalizedString("MO_AVATAR")).foregroundStyle(.primary)
                        Spacer()
                        AccountAvatar(name: profile?.name ?? user?.displayName, url: profile?.avatar ?? user?.picture, size: 30)
                    }
                }
                .disabled(profile == nil)
                LabeledRow(title: NSLocalizedString("MO_ACCOUNT_ID"), value: user?.id ?? "")
                    .contentShape(Rectangle())
                    .onLongPressGesture {
                        UIPasteboard.general.string = user?.id
                        message = NSLocalizedString("MO_ID_COPIED")
                    }
                if let created = profile?.createdAt {
                    LabeledRow(title: NSLocalizedString("MO_JOINED"), value: shortDate(created))
                }
            }
            Section(NSLocalizedString("MO_SECURITY")) {
                Button { verifying = .email } label: {
                    IconRow(icon: "envelope.fill", color: .blue, title: NSLocalizedString("MO_EMAIL"), value: profile?.email ?? user?.email ?? "", chevron: true)
                }
                .disabled(profile == nil)
                Button { verifying = .password } label: {
                    IconRow(icon: "lock.fill", color: .orange,
                            title: NSLocalizedString(profile?.hasPassword == false ? "MO_SET_PASSWORD" : "MO_CHANGE_PASSWORD"), chevron: true)
                }
                .disabled(profile == nil)
                NavigationLink {
                    if let profile { LinkedAccountsView(profile: profile) { verifying = .unlink($0) } }
                } label: {
                    IconRow(icon: "link", color: .indigo, title: NSLocalizedString("MO_LINKED"),
                            value: profile.map { String(format: NSLocalizedString("MO_LINKED_COUNT_%i"), $0.identities.count) } ?? "")
                }
                .disabled(profile == nil)
                NavigationLink { DevicesView() } label: { IconRow(icon: "laptopcomputer.and.iphone", color: .teal, title: NSLocalizedString("MO_DEVICES")) }
            }
            Section(NSLocalizedString("MO_SYNC")) {
                Button { Task { await sync.syncNow(); stats = await LibraryStats.load() } } label: {
                    HStack {
                        IconRow(icon: "arrow.triangle.2.circlepath", color: .green, title: NSLocalizedString("MO_SYNC_NOW"), subtitle: syncLine(sync))
                        if sync.phase == .syncing { ProgressView() }
                    }
                }
                .disabled(sync.phase == .syncing)
            }
            Section {
                Button(NSLocalizedString("MO_SIGN_OUT")) { confirmSignOut = true }.foregroundStyle(Color.accentColor)
            }
            Section {
                NavigationLink { DeleteAccountDataView() } label: { Text(NSLocalizedString("MO_DELETE")).foregroundStyle(.red) }
            }
        }
        .navigationTitle(NSLocalizedString("MO_ACCOUNT"))
        .refreshable { await load(); await sync.syncNow(); stats = await LibraryStats.load() }
        .task { await load(); stats = await LibraryStats.load() }
        .onChange(of: sync.user) { if $0 == nil { dismiss() } }
        .confirmationDialog(NSLocalizedString("MO_AVATAR"), isPresented: $avatarChoice, titleVisibility: .visible) {
            Button(NSLocalizedString("MO_AVATAR_CHOOSE")) { picking = true }
            if profile?.avatar != nil { Button(NSLocalizedString("MO_AVATAR_REMOVE"), role: .destructive) { setAvatar(nil) } }
        }
        .sheet(isPresented: $picking) { ImagePicker(image: $picked) }
        .onChange(of: picked) { image in if let image { picked = nil; setAvatar(image) } }
        .alert(NSLocalizedString("MO_SIGN_OUT"), isPresented: $confirmSignOut) {
            Button(NSLocalizedString("CANCEL"), role: .cancel) {}
            Button(NSLocalizedString("MO_SIGN_OUT"), role: .destructive) { Task { await sync.signOut() } }
        } message: { Text(NSLocalizedString("MO_SIGN_OUT_MESSAGE")) }
        .alert(Text(message ?? ""), isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button(NSLocalizedString("OK")) {}
        }
        .sheet(item: $verifying) { change in
            if let profile {
                NavigationView { VerifyIdentityView(profile: profile) { verification in verifying = nil; verified = (change, verification) } }
            }
        }
        .sheet(isPresented: Binding(get: { verified != nil }, set: { if !$0 { verified = nil } })) {
            if let pending = verified, let profile {
                NavigationView { sensitivePage(pending.0, verification: pending.1, profile: profile) }
            }
        }
    }

    @ViewBuilder
    private func sensitivePage(_ change: SensitiveChange, verification: String, profile: AccountProfile) -> some View {
        let finished = { verified = nil; Task { await load() } }
        switch change {
            case .email: ChangeEmailView(profile: profile, verification: verification) { finished() }
            case .password: ChangePasswordView(profile: profile, verification: verification) { finished() }
            case .unlink(let provider): UnlinkView(provider: provider, verification: verification) { finished() }
        }
    }

    private func stat(_ figure: Int, _ key: String) -> some View {
        VStack(spacing: 3) {
            Text("\(figure)").font(.title3.weight(.semibold))
            Text(NSLocalizedString(key)).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        do {
            let fresh = try await sync.myAccount.profile()
            await sync.account.updateProfile(name: fresh.name, email: fresh.email, picture: fresh.avatar, clearPicture: fresh.avatar == nil)
            profile = fresh
            loadError = nil
        } catch {
            loadError = authErrorText(error)
        }
    }

    private func setAvatar(_ image: UIImage?) {
        uploading = true
        Task {
            do {
                var url = ""
                if let image {
                    guard let data = image.jpegData(compressionQuality: 0.9) else { throw SyncApiError(code: "server") }
                    guard data.count <= 10 * 1024 * 1024 else { message = NSLocalizedString("MO_AVATAR_TOO_LARGE"); uploading = false; return }
                    url = try await sync.api.uploadAvatar(data)
                }
                let updated = try await sync.myAccount.update(avatar: url)
                await sync.account.updateProfile(picture: updated.avatar, clearPicture: updated.avatar == nil)
                profile = updated
                message = NSLocalizedString("MO_SAVED")
            } catch is SyncApiError {
                message = NSLocalizedString("MO_AVATAR_FAILED")
            } catch {
                message = authErrorText(error)
            }
            uploading = false
        }
    }
}

struct LabeledRow: View {
    let title: String
    let value: String
    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
        }
        .accessibilityElement(children: .combine)
    }
}

struct IconRow: View {
    let icon: String
    let color: Color
    let title: String
    var subtitle: String?
    var value = ""
    var chevron = false
    var body: some View {
        HStack(spacing: 15) {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 29, height: 29).background(RoundedRectangle(cornerRadius: 7).fill(color))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer()
            if !value.isEmpty { Text(value).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle) }
            if chevron { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary) }
        }
        .accessibilityElement(children: .combine)
    }
}

struct NicknameView: View {
    let initial: String
    let saved: (AccountProfile) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                AuthField(label: NSLocalizedString("MO_NICKNAME"), text: $name, contentType: .nickname, submit: .done, onSubmit: save)
            } footer: { AuthErrorText(message: error) }
            AuthButton(title: NSLocalizedString("SAVE"), busy: busy, action: save)
        }
        .navigationTitle(NSLocalizedString("MO_NICKNAME"))
        .onAppear { if name.isEmpty { name = initial } }
    }

    private func save() {
        let value = name.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { error = NSLocalizedString("MO_NICKNAME_EMPTY"); return }
        guard value.count <= 24 else { error = NSLocalizedString("MO_NICKNAME_TOO_LONG"); return }
        guard value != initial else { dismiss(); return }
        busy = true
        Task {
            do {
                let updated = try await AccountSync.shared.myAccount.update(name: value)
                await AccountSync.shared.account.updateProfile(name: updated.name)
                saved(updated)
                dismiss()
            } catch {
                self.error = authErrorText(error)
            }
            busy = false
        }
    }
}

/// Proves it is the user: the current password, or for an account without one a code sent to its email.
struct VerifyIdentityView: View {
    let profile: AccountProfile
    let verified: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var code = ""
    @State private var codeVerification = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            if profile.hasPassword {
                Section {
                    AuthField(label: NSLocalizedString("MO_PASSWORD"), text: $password, hint: NSLocalizedString("MO_CURRENT_PASSWORD_HINT"), secure: true,
                              contentType: .password, submit: .go, onSubmit: check)
                } footer: { AuthErrorText(message: error) }
                AuthButton(title: NSLocalizedString("CONTINUE"), busy: busy, action: check)
            } else {
                Section {
                    CodeInput(code: $code, enabled: !busy) { value in run { try await AccountSync.shared.myAccount.confirmCode(email: profile.email ?? "", verification: codeVerification, code: value) } }
                } header: {
                    Text(String(format: NSLocalizedString("MO_CODE_SENT_%@"), profile.email ?? "")).textCase(nil)
                } footer: { AuthErrorText(message: error) }
                ResendCode { await sendCode() }
            }
        }
        .navigationTitle(NSLocalizedString("MO_VERIFY_TITLE"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(NSLocalizedString("CANCEL")) { dismiss() } } }
        .task { if !profile.hasPassword { _ = await sendCode() } }
    }

    private func sendCode() async -> Bool {
        guard let email = profile.email else { return false }
        do { codeVerification = try await AccountSync.shared.myAccount.sendCode(to: email); return true } catch { self.error = authErrorText(error); return false }
    }

    private func check() {
        guard !password.isEmpty else { error = NSLocalizedString("MO_PASSWORD_MISSING"); return }
        run { try await AccountSync.shared.myAccount.verifyPassword(password) }
    }

    private func run(_ verify: @escaping () async throws -> String) {
        busy = true
        error = nil
        Task {
            do { verified(try await verify()) } catch { self.error = authErrorText(error); code = "" }
            busy = false
        }
    }
}

struct ChangePasswordView: View {
    let profile: AccountProfile
    let verification: String
    let done: () -> Void
    @State private var password = ""
    @State private var again = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                AuthField(label: NSLocalizedString("MO_NEW_PASSWORD"), text: $password, hint: NSLocalizedString("MO_PASSWORD_HINT"), secure: true, contentType: .newPassword)
                AuthField(label: NSLocalizedString("MO_PASSWORD_AGAIN"), text: $again, hint: NSLocalizedString("MO_PASSWORD_AGAIN_HINT"), secure: true,
                          contentType: .newPassword, submit: .done, onSubmit: submit)
            } footer: {
                VStack(alignment: .leading, spacing: 8) { PasswordStrengthBar(password: password); AuthErrorText(message: error) }
            }
            AuthButton(title: NSLocalizedString("SAVE"), busy: busy, action: submit)
        }
        .navigationTitle(NSLocalizedString(profile.hasPassword ? "MO_CHANGE_PASSWORD" : "MO_SET_PASSWORD"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(NSLocalizedString("CANCEL"), action: done) } }
    }

    private func submit() {
        if let problem = passwordProblem(password) { error = NSLocalizedString(problem); return }
        guard password == again else { error = NSLocalizedString("MO_PASSWORD_MISMATCH"); return }
        busy = true
        Task {
            do {
                try await AccountSync.shared.myAccount.changePassword(password, verification: verification)
                AccountSync.shared.notice = NSLocalizedString("MO_PASSWORD_CHANGED")
                done()
            }
            catch { self.error = authErrorText(error) }
            busy = false
        }
    }
}

struct ChangeEmailView: View {
    let profile: AccountProfile
    let verification: String
    let done: () -> Void
    @State private var email = ""
    @State private var sentTo: String?
    @State private var codeVerification = ""
    @State private var code = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            if let sentTo {
                Section {
                    CodeInput(code: $code, enabled: !busy, onComplete: confirm)
                } header: {
                    Text(String(format: NSLocalizedString("MO_CODE_SENT_%@"), sentTo)).textCase(nil)
                } footer: { AuthErrorText(message: error) }
                ResendCode {
                    do { codeVerification = try await AccountSync.shared.myAccount.sendCode(to: sentTo); return true } catch { self.error = authErrorText(error); return false }
                }
            } else {
                Section {
                    AuthField(label: NSLocalizedString("MO_NEW_EMAIL"), text: $email, hint: "you@example.com", contentType: .emailAddress,
                              keyboard: .emailAddress, submit: .send, onSubmit: send)
                } footer: { AuthErrorText(message: error) }
                AuthButton(title: NSLocalizedString("MO_SEND_CODE"), busy: busy, busyTitle: NSLocalizedString("MO_SENDING_CODE"), action: send)
            }
        }
        .navigationTitle(NSLocalizedString(sentTo == nil ? "MO_CHANGE_EMAIL" : "MO_CODE_TITLE"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(NSLocalizedString("CANCEL"), action: done) } }
    }

    private func send() {
        let address = email.trimmingCharacters(in: .whitespaces).lowercased()
        guard isEmail(address) else { error = NSLocalizedString("MO_EMAIL_INVALID"); return }
        guard address != profile.email else { error = NSLocalizedString("MO_EMAIL_SAME"); return }
        busy = true
        error = nil
        Task {
            do { codeVerification = try await AccountSync.shared.myAccount.sendCode(to: address); sentTo = address } catch { self.error = authErrorText(error) }
            busy = false
        }
    }

    private func confirm(_ value: String) {
        guard let address = sentTo, !busy else { return }
        busy = true
        error = nil
        Task {
            do {
                let api = AccountSync.shared.myAccount
                let confirmed = try await api.confirmCode(email: address, verification: codeVerification, code: value)
                try await api.changeEmail(address, verification: verification, newEmailVerification: confirmed)
                await AccountSync.shared.account.updateProfile(email: address)
                AccountSync.shared.notice = NSLocalizedString("MO_EMAIL_CHANGED")
                done()
            } catch {
                self.error = authErrorText(error)
                code = ""
            }
            busy = false
        }
    }
}

struct LinkedAccountsView: View {
    let profile: AccountProfile
    let unlink: (String) -> Void
    @State private var confirm: String?
    @State private var message: String?

    private func name(_ provider: String) -> String { provider == "google" ? "Google" : "GitHub" }

    var body: some View {
        List {
            Section {
                ForEach(["google", "github"], id: \.self) { provider in
                    let linked = profile.identities[provider] != nil
                    HStack(spacing: 15) {
                        SocialLogo(provider: provider, size: 24).frame(width: 29)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name(provider))
                            Text(profile.identities[provider].flatMap { $0.isEmpty ? nil : $0 } ?? NSLocalizedString(linked ? "MO_LINKED_YES" : "MO_LINKED_NO"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if linked {
                            Button(NSLocalizedString("MO_UNLINK")) {
                                // Keep at least one way in: a password, or another linked account.
                                if !profile.hasPassword && profile.identities.count <= 1 { message = NSLocalizedString("MO_UNLINK_LAST") } else { confirm = provider }
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .navigationTitle(NSLocalizedString("MO_LINKED"))
        .alert(String(format: NSLocalizedString("MO_UNLINK_TITLE_%@"), name(confirm ?? "")),
               isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })) {
            Button(NSLocalizedString("CANCEL"), role: .cancel) {}
            Button(NSLocalizedString("MO_UNLINK"), role: .destructive) { if let confirm { unlink(confirm) } }
        } message: { Text(NSLocalizedString("MO_UNLINK_MESSAGE")) }
        .alert(Text(message ?? ""), isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button(NSLocalizedString("OK")) {}
        }
    }
}

/// Runs the unlink once the identity check is done, then closes.
struct UnlinkView: View {
    let provider: String
    let verification: String
    let done: () -> Void
    @State private var error: String?

    var body: some View {
        List { Section { if let error { Text(error).foregroundStyle(.red) } else { ProgressView().frame(maxWidth: .infinity) } } }
            .navigationTitle(NSLocalizedString("MO_LINKED"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(NSLocalizedString("CANCEL"), action: done) } }
            .task {
                do { try await AccountSync.shared.myAccount.unlinkSocial(provider, verification: verification); done() }
                catch { self.error = authErrorText(error) }
            }
    }
}

struct DevicesView: View {
    @State private var devices: [AccountDevice]?
    @State private var failed = false
    @State private var confirm: AccountDevice?

    var body: some View {
        Group {
            if let devices {
                List {
                    Section {
                        ForEach(devices) { device in
                            HStack(spacing: 15) {
                                Image(systemName: device.platform == "ios" || device.platform == "android" ? "iphone" : "laptopcomputer")
                                    .foregroundStyle(.white).frame(width: 29, height: 29)
                                    .background(RoundedRectangle(cornerRadius: 7).fill(device.current ? Color.green : Color.gray))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(device.name)
                                    Text(device.current ? NSLocalizedString("MO_THIS_DEVICE") : relative(device.lastSeen, synced: false))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if !device.current {
                                    Button(NSLocalizedString("MO_DEVICE_REMOVE")) { confirm = device }.buttonStyle(.borderless)
                                }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .refreshable { await load() }
            } else if failed {
                VStack(spacing: 12) {
                    Image(systemName: "wifi.slash").font(.largeTitle).foregroundStyle(.secondary)
                    Text(NSLocalizedString("MO_NETWORK_ERROR")).font(.headline)
                    Button(NSLocalizedString("RETRY")) { Task { await load() } }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(NSLocalizedString("MO_DEVICES"))
        .task { await load() }
        .alert(String(format: NSLocalizedString("MO_DEVICE_REMOVE_TITLE_%@"), confirm?.name ?? ""),
               isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })) {
            Button(NSLocalizedString("CANCEL"), role: .cancel) {}
            Button(NSLocalizedString("MO_DEVICE_REMOVE"), role: .destructive) {
                guard let device = confirm else { return }
                Task { try? await AccountSync.shared.api.removeDevice(device.id); await load() }
            }
        } message: { Text(NSLocalizedString("MO_DEVICE_REMOVE_MESSAGE")) }
    }

    private func load() async {
        do { devices = try await AccountSync.shared.api.devices(); failed = false } catch { failed = devices == nil }
    }
}

/// Deletes this app's data from the account; the 物语 account itself is kept.
struct DeleteAccountDataView: View {
    @State private var typed = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let phrase = NSLocalizedString("MO_DELETE_PHRASE")
        List {
            Section {
                Text(NSLocalizedString("MO_DELETE_MESSAGE")).font(.subheadline).foregroundStyle(.secondary)
            }
            Section(String(format: NSLocalizedString("MO_DELETE_TYPE_%@"), phrase)) {
                AuthField(label: NSLocalizedString("MO_DELETE_CONFIRM"), text: $typed, hint: phrase, submit: .done)
            }
            Section { AuthErrorText(message: error) }.listRowBackground(Color.clear)
            AuthButton(title: NSLocalizedString("MO_DELETE"), busy: busy, role: .destructive, enabled: typed.trimmingCharacters(in: .whitespaces) == phrase) {
                busy = true
                Task {
                    do {
                        try await AccountSync.shared.deleteAppData()
                        AccountSync.shared.notice = NSLocalizedString("MO_DELETED")
                    } catch { self.error = NSLocalizedString("MO_NETWORK_ERROR") }
                    busy = false
                }
            }
        }
        .navigationTitle(NSLocalizedString("MO_DELETE"))
    }
}
