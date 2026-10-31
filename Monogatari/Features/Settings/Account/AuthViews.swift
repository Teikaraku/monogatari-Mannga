//
//  AuthViews.swift
//  Monogatari
//
//  The 物语 account's own sign-in, registration and password reset, plus the form pieces the account
//  pages share. Mirrors android/.../ui/AuthScreens.kt and AccountWidgets.kt.
//

import AuthenticationServices
import SwiftUI

// MARK: - Checks and messages

func isEmail(_ value: String) -> Bool {
    value.trimmingCharacters(in: .whitespaces).range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
}

/// The identity center's policy, checked first here: 8 to 72 characters, at least two kinds. The key of the problem.
func passwordProblem(_ value: String) -> String? {
    if value.count < 8 { return "MO_PASSWORD_TOO_SHORT" }
    if value.count > 72 { return "MO_PASSWORD_TOO_LONG" }
    let kinds = ["[a-z]", "[A-Z]", "\\d", "[^A-Za-z0-9]"].filter { value.range(of: $0, options: .regularExpression) != nil }.count
    return kinds < 2 ? "MO_PASSWORD_TYPES" : nil
}

/// 0 to 3, for the strength bar.
func passwordStrength(_ value: String) -> Int {
    guard !value.isEmpty else { return 0 }
    var score = value.count >= 8 ? 1 : 0
    if value.range(of: "[A-Za-z]", options: .regularExpression) != nil && value.range(of: "\\d", options: .regularExpression) != nil { score += 1 }
    if value.count >= 12 || value.range(of: "[^A-Za-z0-9]", options: .regularExpression) != nil { score += 1 }
    return score
}

/// What to tell the user about a failed request.
func authErrorText(_ error: Error) -> String {
    if let error = error as? AccountError {
        return NSLocalizedString(error.code == "network" ? "MO_NETWORK_ERROR" : error.code == "signed_out" ? "MO_SYNC_SIGNED_OUT" : "MO_ERROR_GENERIC")
    }
    if let error = error as? SyncApiError { return NSLocalizedString(error.code == "network" ? "MO_NETWORK_ERROR" : "MO_ERROR_GENERIC") }
    guard let failure = error as? AuthFailure else { return NSLocalizedString("MO_ERROR_GENERIC") }
    if !failure.passwordIssues.isEmpty || failure.code.hasPrefix("password") {
        for issue in failure.passwordIssues {
            switch issue {
                case "password_rejected.pwned": return NSLocalizedString("MO_PASSWORD_PWNED")
                case "password_rejected.restricted.sequence", "password_rejected.restricted.repetition": return NSLocalizedString("MO_PASSWORD_SEQUENCE")
                case "password_rejected.restricted.user_info": return NSLocalizedString("MO_PASSWORD_USER_INFO")
                case "password_rejected.too_short": return NSLocalizedString("MO_PASSWORD_TOO_SHORT")
                case "password_rejected.too_long": return NSLocalizedString("MO_PASSWORD_TOO_LONG")
                case "password_rejected.character_types": return NSLocalizedString("MO_PASSWORD_TYPES")
                default: continue
            }
        }
        return NSLocalizedString("MO_PASSWORD_TYPES")
    }
    switch failure.code {
        case "session.invalid_credentials": return NSLocalizedString("MO_WRONG_PASSWORD")
        case "session.verification_blocked_too_many_attempts": return NSLocalizedString("MO_LOCKED")
        case "user.suspended": return NSLocalizedString("MO_SUSPENDED")
        case "verification_code.code_mismatch": return NSLocalizedString("MO_CODE_WRONG")
        case "verification_code.expired": return NSLocalizedString("MO_CODE_EXPIRED")
        case "verification_code.exceed_max_try": return NSLocalizedString("MO_CODE_TOO_MANY")
        case "user.email_already_in_use": return NSLocalizedString("MO_EMAIL_TAKEN")
        case "user.identity_not_exist", "user.email_not_exist": return NSLocalizedString("MO_EMAIL_UNKNOWN")
        case "session.not_found", "session_lost", "session.verification_session_not_found", "session.verification_failed",
             "verification_record.not_found", "verification_record.permission_denied":
            return NSLocalizedString("MO_SESSION_LOST")
        case "connector.general": return NSLocalizedString("MO_SOCIAL_UNAVAILABLE")
        case "gate_rate", "request.too_many_requests":
            if let wait = failure.retryAfter, wait < 120 { return String(format: NSLocalizedString("MO_TOO_MANY_SECONDS_%i"), wait) }
            return NSLocalizedString("MO_TOO_MANY")
        case "gate_pow": return NSLocalizedString("MO_BOT_CHECK_FAILED")
        case "gate_unavailable": return NSLocalizedString("MO_BOT_CHECK_UNAVAILABLE")
        case "network": return NSLocalizedString("MO_NETWORK_ERROR")
        case "user.identity_already_in_use", "user.social_account_exists_in_profile": return NSLocalizedString("MO_SOCIAL_ALREADY_LINKED")
        default: return NSLocalizedString("MO_ERROR_GENERIC")
    }
}

/// The window to present the web sign-in sheet from.
@MainActor func presentationAnchor() -> ASPresentationAnchor {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
}

// MARK: - Form pieces

/// A labelled field on a grouped row; password fields can show their text.
struct AuthField: View {
    let label: String
    @Binding var text: String
    var hint = ""
    var secure = false
    var contentType: UITextContentType?
    var keyboard: UIKeyboardType = .default
    var submit: SubmitLabel = .next
    var onSubmit: () -> Void = {}
    @State private var revealed = false

    var body: some View {
        HStack(spacing: 12) {
            Text(label).frame(width: 76, alignment: .leading)
            Group {
                if secure && !revealed {
                    SecureField(hint, text: $text)
                } else {
                    TextField(hint, text: $text)
                }
            }
            .textContentType(contentType)
            .keyboardType(keyboard)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(submit)
            .onSubmit(onSubmit)
            .accessibilityLabel(label)
            if secure {
                Button { revealed.toggle() } label: {
                    Image(systemName: revealed ? "eye.slash" : "eye").foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(NSLocalizedString(revealed ? "MO_HIDE_PASSWORD" : "MO_SHOW_PASSWORD"))
            }
        }
    }
}

/// The full-width primary button under a form.
struct AuthButton: View {
    let title: String
    var busy = false
    var busyTitle: String?
    var role: ButtonRole?
    var enabled = true
    let action: () -> Void

    var body: some View {
        Section {
            Button(role: role, action: action) {
                HStack(spacing: 10) {
                    Spacer()
                    if busy {
                        ProgressView().tint(.white)
                        if let busyTitle { Text(busyTitle) }
                    } else {
                        Text(title).fontWeight(.semibold)
                    }
                    Spacer()
                }
                .foregroundStyle(.white)
                .padding(.vertical, 4)
            }
            .disabled(busy || !enabled)
            .listRowBackground((role == .destructive ? Color.red : Color.accentColor).opacity(busy || !enabled ? 0.45 : 1))
        }
    }
}

/// The red line explaining the last failure, read out by VoiceOver.
struct AuthErrorText: View {
    let message: String?
    var body: some View {
        if let message {
            Text(message).font(.subheadline).foregroundStyle(.red)
                .accessibilityAddTraits(.updatesFrequently)
                .onAppear { UIAccessibility.post(notification: .announcement, argument: message) }
        }
    }
}

struct PasswordStrengthBar: View {
    let password: String
    var body: some View {
        let score = passwordStrength(password)
        let colors: [Color] = [.red, .orange, .green]
        let labels = ["MO_STRENGTH_WEAK", "MO_STRENGTH_FAIR", "MO_STRENGTH_STRONG"].map { NSLocalizedString($0) }
        HStack(spacing: 6) {
            ForEach(0..<3, id: \.self) { index in
                Capsule().fill(index < score ? colors[score - 1] : Color(.systemFill)).frame(height: 4)
            }
            Text(score == 0 ? "" : labels[score - 1]).font(.caption).foregroundStyle(.secondary).frame(width: 28, alignment: .leading)
        }
        .animation(.easeOut(duration: 0.2), value: score)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(score == 0 ? "" : labels[score - 1])
    }
}

/// Six digit boxes; a hidden field takes typing, pasting and the keyboard's one-time-code suggestion.
struct CodeInput: View {
    @Binding var code: String
    var enabled = true
    let onComplete: (String) -> Void
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($focused)
                .opacity(0.02)
                .disabled(!enabled)
                .onChange(of: code) { value in
                    let digits = String(value.filter(\.isNumber).prefix(6))
                    if digits != value { code = digits }
                    if digits.count == 6 { onComplete(digits) }
                }
            HStack(spacing: 8) {
                ForEach(0..<6, id: \.self) { index in
                    let characters = Array(code)
                    Text(index < characters.count ? String(characters[index]) : "")
                        .font(.title.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                        .overlay(RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(focused && index == characters.count ? Color.accentColor : .clear, lineWidth: 1.5))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(NSLocalizedString("MO_CODE_LABEL"))
            .accessibilityValue(code)
        }
        .onAppear { focused = true }
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}

/// The resend link with its 60-second countdown.
struct ResendCode: View {
    let resend: () async -> Bool
    @State private var left = 60
    @State private var sending = false
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Button {
            sending = true
            Task {
                if await resend() { left = 60 }
                sending = false
            }
        } label: {
            Text(left > 0 ? String(format: NSLocalizedString("MO_RESEND_IN_%i"), left) : NSLocalizedString("MO_RESEND"))
                .frame(maxWidth: .infinity)
        }
        .disabled(left > 0 || sending)
        .onReceive(timer) { _ in if left > 0 { left -= 1 } }
        .listRowBackground(Color.clear)
    }
}

/// The Google "G" and the GitHub mark (vector assets; GitHub's follows the text colour).
struct SocialLogo: View {
    let provider: String
    var size: CGFloat = 20
    var body: some View {
        Image(provider == "google" ? "SocialGoogle" : "SocialGithub")
            .resizable()
            .renderingMode(provider == "google" ? .original : .template)
            .foregroundStyle(.primary)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct SocialButtons: View {
    let busy: String?
    let select: (String) -> Void
    var body: some View {
        Section {
            ForEach(["google", "github"], id: \.self) { provider in
                Button { select(provider) } label: {
                    HStack(spacing: 10) {
                        Spacer()
                        if busy == provider { ProgressView() } else { SocialLogo(provider: provider) }
                        Text(NSLocalizedString(provider == "google" ? "MO_CONTINUE_GOOGLE" : "MO_CONTINUE_GITHUB")).foregroundStyle(.primary)
                        Spacer()
                    }
                }
                .disabled(busy != nil)
            }
        } header: {
            Text(NSLocalizedString("MO_OR")).frame(maxWidth: .infinity)
        }
    }
}

/// A round profile picture: the uploaded one, else the first letter of the name, else a person icon.
struct AccountAvatar: View {
    let name: String?
    let url: String?
    var size: CGFloat = 48

    var body: some View {
        let letter = name?.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() }
        let fallback = ZStack {
            Circle().fill(letter == nil ? Color(.systemGray3) : Color.accentColor)
            if let letter {
                Text(letter).font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(.white)
            } else {
                Image(systemName: "person.fill").font(.system(size: size * 0.5)).foregroundStyle(.white)
            }
        }
        Group {
            if let url, let link = URL(string: url) {
                AsyncImage(url: link) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() } else { fallback }
                }
            } else {
                fallback
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

// MARK: - Sign-in flow

/// Closes the sign-in pages once signed in.
private struct AuthDismiss: EnvironmentKey { static let defaultValue: () -> Void = {} }
extension EnvironmentValues {
    var authDone: () -> Void {
        get { self[AuthDismiss.self] }
        set { self[AuthDismiss.self] = newValue }
    }
}

/// The sheet the sign-in pages live in, with its own navigation.
struct AuthFlowView: View {
    var register = false
    @Environment(\.dismiss) private var dismiss
    @State private var welcome: String?

    var body: some View {
        NavigationView {
            Group { if register { EmailStepView(purpose: .register) } else { SignInView() } }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(NSLocalizedString("CANCEL")) { dismiss() } } }
        }
        .navigationViewStyle(.stack)
        .environment(\.authDone) {
            let name = AccountSync.shared.user?.displayName ?? ""
            AccountSync.shared.notice = String(format: NSLocalizedString("MO_WELCOME_%@"), name)
            dismiss()
        }
    }
}

struct SignInView: View {
    @Environment(\.authDone) private var done
    @State private var email = ""
    @State private var password = ""
    @State private var remember = false
    @State private var error: String?
    @State private var busy = false
    @State private var social: String?
    private let saved = KeychainItem(account: "remembered")

    var body: some View {
        List {
            Section {
                AuthField(label: NSLocalizedString("MO_EMAIL"), text: $email, hint: "you@example.com", contentType: .username, keyboard: .emailAddress)
                AuthField(label: NSLocalizedString("MO_PASSWORD"), text: $password, hint: NSLocalizedString("MO_PASSWORD_HINT"), secure: true,
                          contentType: .password, submit: .go, onSubmit: submit)
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Toggle(isOn: Binding(get: { remember }, set: { value in
                            remember = value
                            // Unchecking forgets a saved password at once.
                            if !value, let email = savedCredentials()?.email { save(email: email, password: nil) }
                        })) { Text(NSLocalizedString("MO_REMEMBER_PASSWORD")) }
                            .toggleStyle(CheckToggleStyle())
                        Spacer()
                        NavigationLink(NSLocalizedString("MO_FORGOT_PASSWORD")) { EmailStepView(purpose: .forgot, initialEmail: email) }
                    }
                    AuthErrorText(message: error)
                }
            }
            AuthButton(title: NSLocalizedString("MO_SIGN_IN"), busy: busy, busyTitle: NSLocalizedString("MO_SIGNING_IN"), action: submit)
            SocialButtons(busy: social, select: signIn(with:))
            Section {
                NavigationLink { EmailStepView(purpose: .register, initialEmail: email) } label: {
                    HStack { Text(NSLocalizedString("MO_NO_ACCOUNT")).foregroundStyle(.secondary); Text(NSLocalizedString("MO_REGISTER")).foregroundStyle(Color.accentColor) }
                }
            }
        }
        .navigationTitle(NSLocalizedString("MO_SIGN_IN_TITLE"))
        .onAppear {
            guard email.isEmpty, let saved = savedCredentials() else { return }
            email = saved.email
            if let password = saved.password { self.password = password; remember = true }
        }
    }

    private func savedCredentials() -> (email: String, password: String?)? {
        guard let raw = saved.read(), let json = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: String],
              let email = json["email"], !email.isEmpty else { return nil }
        return (email, json["password"].flatMap { $0.isEmpty ? nil : $0 })
    }

    private func save(email: String, password: String?) {
        var json = ["email": email]
        if let password { json["password"] = password }
        if let data = try? JSONSerialization.data(withJSONObject: json) { saved.write(String(decoding: data, as: UTF8.self)) }
    }

    private func submit() {
        let address = email.trimmingCharacters(in: .whitespaces).lowercased()
        guard isEmail(address) else { error = NSLocalizedString("MO_EMAIL_INVALID"); return }
        guard !password.isEmpty else { error = NSLocalizedString("MO_PASSWORD_MISSING"); return }
        busy = true
        error = nil
        Task {
            do {
                let interaction = AuthInteraction()
                try await interaction.start("SignIn")
                try await interaction.identify(try await interaction.verifyPassword(email: address, password: password))
                save(email: address, password: remember ? password : nil)
                try await finish(interaction)
            } catch {
                self.error = authErrorText(error)
            }
            busy = false
        }
    }

    private func finish(_ interaction: AuthInteraction) async throws {
        try await AccountSync.shared.completeSignIn(code: try await interaction.submit(), verifier: interaction.codeVerifier)
        done()
    }

    private func signIn(with provider: String) {
        social = provider
        Task {
            do {
                try await AccountSync.shared.signInWithBrowser(provider, anchor: presentationAnchor())
                done()
            } catch let failure as AccountError where failure.code == "cancelled" {
            } catch {
                self.error = NSLocalizedString("MO_SOCIAL_UNAVAILABLE")
            }
            social = nil
        }
    }
}

/// The round check box of "remember password".
struct CheckToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(spacing: 8) {
                Image(systemName: configuration.isOn ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(configuration.isOn ? Color.accentColor : .secondary)
                configuration.label.foregroundStyle(.primary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isButton, .isSelected] : .isButton)
    }
}

enum CodePurpose { case register, forgot }

/// Registration and reset, step 1: the email.
struct EmailStepView: View {
    let purpose: CodePurpose
    var initialEmail = ""
    @Environment(\.authDone) private var done
    @State private var email = ""
    @State private var error: String?
    @State private var busy = false
    @State private var social: String?
    @State private var next: (AuthInteraction, String, String)?

    var body: some View {
        List {
            Section {
                AuthField(label: NSLocalizedString("MO_EMAIL"), text: $email, hint: "you@example.com", contentType: .emailAddress,
                          keyboard: .emailAddress, submit: .send, onSubmit: submit)
            } footer: { AuthErrorText(message: error) }
            AuthButton(title: NSLocalizedString("MO_SEND_CODE"), busy: busy, busyTitle: NSLocalizedString("MO_SENDING_CODE"), action: submit)
            if purpose == .register {
                Section {
                    NavigationLink { SignInView() } label: {
                        HStack { Text(NSLocalizedString("MO_HAVE_ACCOUNT")).foregroundStyle(.secondary); Text(NSLocalizedString("MO_SIGN_IN")).foregroundStyle(Color.accentColor) }
                    }
                }
                SocialButtons(busy: social) { provider in
                    social = provider
                    Task {
                        do { try await AccountSync.shared.signInWithBrowser(provider, anchor: presentationAnchor()); done() }
                        catch let failure as AccountError where failure.code == "cancelled" {}
                        catch { self.error = NSLocalizedString("MO_SOCIAL_UNAVAILABLE") }
                        social = nil
                    }
                }
            }
        }
        .navigationTitle(NSLocalizedString(purpose == .register ? "MO_REGISTER_TITLE" : "MO_FORGOT_TITLE"))
        .onAppear { if email.isEmpty { email = initialEmail } }
        .background(NavigationLink(isActive: Binding(get: { next != nil }, set: { if !$0 { next = nil } })) {
            if let next { CodeStepView(purpose: purpose, interaction: next.0, email: next.1, verification: next.2) }
        } label: { EmptyView() })
    }

    private func submit() {
        let address = email.trimmingCharacters(in: .whitespaces).lowercased()
        guard isEmail(address) else { error = NSLocalizedString("MO_EMAIL_INVALID"); return }
        busy = true
        error = nil
        let event = purpose == .register ? "Register" : "ForgotPassword"
        Task {
            do {
                let interaction = AuthInteraction()
                try await interaction.start(event)
                next = (interaction, address, try await interaction.sendCode(event, email: address))
            } catch {
                self.error = authErrorText(error)
            }
            busy = false
        }
    }
}

/// The 6-digit code sent to the email.
struct CodeStepView: View {
    let purpose: CodePurpose
    let interaction: AuthInteraction
    let email: String
    @State var verification: String
    @Environment(\.authDone) private var done
    @State private var code = ""
    @State private var error: String?
    @State private var busy = false
    @State private var passwordStep = false

    var body: some View {
        List {
            Section {
                CodeInput(code: $code, enabled: !busy, onComplete: verify)
            } header: {
                Text(String(format: NSLocalizedString("MO_CODE_SENT_%@"), email)).textCase(nil)
            } footer: {
                VStack { AuthErrorText(message: error); if busy { ProgressView().frame(maxWidth: .infinity) } }
            }
            ResendCode {
                do {
                    verification = try await interaction.sendCode(purpose == .register ? "Register" : "ForgotPassword", email: email)
                    error = NSLocalizedString("MO_CODE_RESENT")
                    return true
                } catch {
                    self.error = authErrorText(error)
                    return false
                }
            }
        }
        .navigationTitle(NSLocalizedString("MO_CODE_TITLE"))
        .background(NavigationLink(isActive: $passwordStep) { NewPasswordView(purpose: purpose, interaction: interaction) } label: { EmptyView() })
    }

    private func verify(_ value: String) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            do {
                let verified = try await interaction.verifyCode(email: email, verification: verification, code: value)
                if purpose == .register {
                    do {
                        try await interaction.addEmail(verified)
                    } catch let failure as AuthFailure where failure.code == "user.email_already_in_use" {
                        // Already registered: the verified email signs straight in.
                        try await interaction.switchEvent("SignIn")
                        try await interaction.identify(verified)
                        try await AccountSync.shared.completeSignIn(code: try await interaction.submit(), verifier: interaction.codeVerifier)
                        done()
                        AccountSync.shared.notice = NSLocalizedString("MO_ALREADY_REGISTERED")
                        return
                    }
                } else {
                    try await interaction.identify(verified)
                }
                passwordStep = true
            } catch {
                self.error = authErrorText(error)
                code = ""
            }
            busy = false
        }
    }
}

/// Registration step 3, or the new password after a reset; both end signed in.
struct NewPasswordView: View {
    let purpose: CodePurpose
    let interaction: AuthInteraction
    @Environment(\.authDone) private var done
    @State private var password = ""
    @State private var again = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                AuthField(label: NSLocalizedString("MO_PASSWORD"), text: $password, hint: NSLocalizedString("MO_PASSWORD_HINT"), secure: true, contentType: .newPassword)
                AuthField(label: NSLocalizedString("MO_PASSWORD_AGAIN"), text: $again, hint: NSLocalizedString("MO_PASSWORD_AGAIN_HINT"), secure: true,
                          contentType: .newPassword, submit: .done, onSubmit: submit)
            } footer: {
                VStack(alignment: .leading, spacing: 8) { PasswordStrengthBar(password: password); AuthErrorText(message: error) }
            }
            AuthButton(title: NSLocalizedString(purpose == .register ? "MO_FINISH_REGISTER" : "MO_SAVE_AND_SIGN_IN"), busy: busy, action: submit)
        }
        .navigationTitle(NSLocalizedString(purpose == .register ? "MO_SET_PASSWORD_TITLE" : "MO_NEW_PASSWORD_TITLE"))
    }

    private func submit() {
        if let problem = passwordProblem(password) { error = NSLocalizedString(problem); return }
        guard password == again else { error = NSLocalizedString("MO_PASSWORD_MISMATCH"); return }
        busy = true
        error = nil
        Task {
            do {
                if purpose == .register {
                    try await interaction.setPassword(password)
                    try await interaction.identify()
                } else {
                    try await interaction.resetPassword(password)
                }
                try await AccountSync.shared.completeSignIn(code: try await interaction.submit(), verifier: interaction.codeVerifier)
                done()
            } catch {
                self.error = authErrorText(error)
            }
            busy = false
        }
    }
}
