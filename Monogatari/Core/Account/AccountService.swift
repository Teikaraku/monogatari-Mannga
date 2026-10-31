//
//  AccountService.swift
//  Monogatari
//
//  The 物语 account session: tokens in the Keychain, the sync token refreshed with the resource and the
//  Account API token without it. Refresh tokens rotate, so the actor runs one refresh at a time and
//  saves each new refresh token before the next. Mirrors android/.../account/AccountService.kt.
//

import AuthenticationServices
import Combine
import Foundation
import Security

/// A Keychain item for this app (this device only, available after first unlock so background sync works).
struct KeychainItem {
    let account: String
    private let service = "app.monogatari.account"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func read() -> String? {
        var item: CFTypeRef?
        var search = query
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        guard SecItemCopyMatching(search as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ value: String) {
        let data = Data(value.utf8)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
        }
    }

    func clear() { SecItemDelete(query as CFDictionary) }
}

struct AccountError: Error, Equatable {
    /// cancelled, network, rejected, invalid_state, signed_out
    let code: String
}

struct AccountUser: Codable, Equatable {
    let id: String
    var name: String?
    var email: String?
    var picture: String?

    /// The name, else the part of the email before @, else a short id.
    var displayName: String {
        if let name = name?.trimmingCharacters(in: .whitespaces), !name.isEmpty { return name }
        if let email, let at = email.firstIndex(of: "@") { return String(email[..<at]) }
        return String(id.prefix(8))
    }

    /// The ID token came straight from the token endpoint over TLS for this app's verifier; nothing on a server trusts it.
    init(idToken: String) throws {
        let claims = try jwtClaims(idToken)
        guard let sub = claims["sub"] as? String, !sub.isEmpty else { throw AccountError(code: "rejected") }
        func text(_ key: String) -> String? { (claims[key] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        id = sub; name = text("name") ?? text("username"); email = text("email"); picture = text("picture")
    }
}

struct AccountTokens: Codable, Equatable {
    var accessToken: String
    var expiresAt: Date
    var refreshToken: String?
    var idToken: String?

    /// Refreshed a minute early so a request never leaves with a token about to lapse.
    func expiresSoon(_ now: Date) -> Bool { now >= expiresAt.addingTimeInterval(-60) }
}

func jwtClaims(_ token: String) throws -> [String: Any] {
    let parts = token.split(separator: ".")
    guard parts.count == 3 else { throw AccountError(code: "rejected") }
    var payload = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while payload.count % 4 != 0 { payload += "=" }
    guard let data = Data(base64Encoded: payload), let claims = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        throw AccountError(code: "rejected")
    }
    return claims
}

actor AccountService {
    static let shared = AccountService()

    private let vault = KeychainItem(account: "session")
    private let session: URLSession
    private var tokens: AccountTokens?
    private var accountToken: (String, Date)?
    private let now: () -> Date

    /// The signed-in user, for the UI (published on the main actor).
    @MainActor let user = CurrentValueSubject<AccountUser?, Never>(nil)

    init(session: URLSession = .shared, now: @escaping () -> Date = Date.init) {
        self.session = session
        self.now = now
        if let raw = vault.read(), let data = raw.data(using: .utf8),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            tokens = saved.tokens
            let restored = saved.user
            Task { @MainActor in self.user.value = restored }
        }
    }

    private struct Saved: Codable { let tokens: AccountTokens; let user: AccountUser }

    var signedIn: Bool { tokens != nil }

    private func save(_ user: AccountUser?) {
        if let tokens, let user, let data = try? JSONEncoder().encode(Saved(tokens: tokens, user: user)) {
            vault.write(String(decoding: data, as: UTF8.self))
        } else {
            vault.clear()
        }
    }

    private func publish(_ user: AccountUser?) async {
        save(user)
        await MainActor.run { self.user.value = user }
    }

    private func tokenRequest(_ form: [String: String], previousRefresh: String?) async throws -> AccountTokens {
        var request = URLRequest(url: URL(string: "\(AccountConfig.issuer)/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Self.formBody(form)
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) } catch { throw AccountError(code: "network") }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if (400..<500).contains(status) { throw AccountError(code: "rejected") }
        guard (200..<300).contains(status), let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let access = json["access_token"] as? String, !access.isEmpty else {
            throw AccountError(code: status >= 500 ? "network" : "rejected")
        }
        let expires = (json["expires_in"] as? NSNumber)?.doubleValue ?? 3600
        // Logto rotates refresh tokens; keep the old one only when no new one was issued.
        return AccountTokens(accessToken: access, expiresAt: now().addingTimeInterval(expires),
                             refreshToken: json["refresh_token"] as? String ?? previousRefresh, idToken: json["id_token"] as? String)
    }

    static func formBody(_ form: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(form.sorted { $0.key < $1.key }.map {
            "\($0.key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed)!)"
        }.joined(separator: "&").utf8)
    }

    /// Exchanges the code a sign-in ended with for the session, and keeps it.
    @discardableResult
    func completeSignIn(code: String, verifier: String) async throws -> AccountUser {
        // With the resource the first access token is already the sync service's JWT.
        let next = try await tokenRequest([
            "grant_type": "authorization_code", "code": code, "redirect_uri": AccountConfig.redirectUri,
            "code_verifier": verifier, "client_id": AccountConfig.clientId, "resource": AccountConfig.resource
        ], previousRefresh: nil)
        guard let id = next.idToken else { throw AccountError(code: "rejected") }
        let user = try AccountUser(idToken: id)
        tokens = next
        accountToken = nil
        await publish(user)
        return user
    }

    /// Google or GitHub: the provider's page opens in the system web sheet and comes back to the app.
    @MainActor
    func signInWithBrowser(_ provider: String, anchor: ASPresentationAnchor) async throws -> AccountUser {
        let verifier = PKCE.random(48)
        let state = PKCE.random(24)
        let url = PKCE.authorizeUrl(state: state, challenge: PKCE.challenge(verifier), extra: ["direct_sign_in": "social:\(provider)-universal"])
        let returned: URL = try await withCheckedThrowingContinuation { continuation in
            let web = ASWebAuthenticationSession(url: url, callbackURLScheme: AccountConfig.callbackScheme) { url, error in
                if let url { continuation.resume(returning: url); return }
                let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                continuation.resume(throwing: AccountError(code: cancelled ? "cancelled" : "network"))
            }
            let provider = AnchorProvider(anchor)
            web.presentationContextProvider = provider
            web.prefersEphemeralWebBrowserSession = false
            objc_setAssociatedObject(web, &AnchorProvider.key, provider, .OBJC_ASSOCIATION_RETAIN)
            if !web.start() { continuation.resume(throwing: AccountError(code: "network")) }
        }
        let code: String
        do { code = try PKCE.authorizationCode(returned, state: state) } catch let failure as AuthFailure {
            throw AccountError(code: failure.code == "cancelled" ? "cancelled" : failure.code == "invalid_state" ? "invalid_state" : "rejected")
        }
        return try await completeSignIn(code: code, verifier: verifier)
    }

    /// Shows edits made in the app until the next ID token.
    func updateProfile(name: String? = nil, email: String? = nil, picture: String? = nil, clearPicture: Bool = false) async {
        guard var current = await MainActor.run(body: { self.user.value }) else { return }
        if let name { current.name = name }
        if let email { current.email = email }
        current.picture = clearPicture ? nil : picture ?? current.picture
        await publish(current)
    }

    /// A current access token for the sync service. A refresh the server refuses signs the user out.
    func accessToken(forceRefresh: Bool = false) async throws -> String {
        guard let seen = tokens else { throw AccountError(code: "signed_out") }
        if !forceRefresh && !seen.expiresSoon(now()) { return seen.accessToken }
        return try await serialized { [self] in
            guard let current = tokens else { throw AccountError(code: "signed_out") }
            // Another caller refreshed while this one waited.
            if current != seen && !current.expiresSoon(now()) { return current.accessToken }
            let next = try await refresh(current, resource: true)
            tokens = AccountTokens(accessToken: next.accessToken, expiresAt: next.expiresAt, refreshToken: next.refreshToken,
                                   idToken: next.idToken ?? current.idToken)
            save(await MainActor.run { self.user.value })
            return next.accessToken
        }
    }

    /// A token for the identity center's Account API, which takes only tokens issued without a resource.
    func accountApiToken(forceRefresh: Bool = false) async throws -> String {
        if !forceRefresh, let (token, expires) = accountToken, now() < expires.addingTimeInterval(-60) { return token }
        return try await serialized { [self] in
            guard let current = tokens else { throw AccountError(code: "signed_out") }
            let next = try await refresh(current, resource: false)
            // Keep the sync token; only the rotated refresh token moves on.
            tokens = AccountTokens(accessToken: current.accessToken, expiresAt: current.expiresAt, refreshToken: next.refreshToken,
                                   idToken: next.idToken ?? current.idToken)
            save(await MainActor.run { self.user.value })
            accountToken = (next.accessToken, next.expiresAt)
            return next.accessToken
        }
    }

    private func refresh(_ current: AccountTokens, resource: Bool) async throws -> AccountTokens {
        guard let refresh = current.refreshToken else { await forget(); throw AccountError(code: "signed_out") }
        var form = ["grant_type": "refresh_token", "refresh_token": refresh, "client_id": AccountConfig.clientId]
        if resource { form["resource"] = AccountConfig.resource }
        do {
            return try await tokenRequest(form, previousRefresh: refresh)
        } catch let error as AccountError where error.code == "rejected" {
            await forget()
            throw AccountError(code: "signed_out")
        }
    }

    /// Refreshes run strictly one after another: the actor alone would let a second caller in at the
    /// first one's network await, and both would present the same (then revoked) refresh token.
    private var lastRefresh: Task<Void, Never>?

    private func serialized<T: Sendable>(_ body: @escaping @Sendable () async throws -> T) async throws -> T {
        let previous = lastRefresh
        let task = Task { () async throws -> T in
            await previous?.value
            return try await body()
        }
        lastRefresh = Task { _ = try? await task.value }
        return try await task.value
    }

    private func forget() async {
        tokens = nil
        accountToken = nil
        await publish(nil)
    }

    /// Signs out here; the refresh token is revoked so it cannot be used again.
    func signOut() async {
        let refresh = tokens?.refreshToken
        await forget()
        guard let refresh else { return }
        var request = URLRequest(url: URL(string: "\(AccountConfig.issuer)/token/revocation")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody(["token": refresh, "token_type_hint": "refresh_token", "client_id": AccountConfig.clientId])
        _ = try? await session.data(for: request)
    }
}

private final class AnchorProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static var key = 0
    let anchor: ASPresentationAnchor
    init(_ anchor: ASPresentationAnchor) { self.anchor = anchor }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { anchor }
}
