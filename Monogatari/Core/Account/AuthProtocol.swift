//
//  AuthProtocol.swift
//  Monogatari
//
//  The 物语 account's native sign-in: Logto's Experience API driven with the interaction cookies of an
//  OIDC authorization request (code + PKCE), behind the identity center's proof-of-work gate.
//  Mirrors android/.../account/AuthProtocol.kt.
//

import CryptoKit
import Foundation

enum AccountConfig {
    static let issuer = "https://auth.monogatari.top/oidc"
    static let origin = "https://auth.monogatari.top"
    /// The 物语漫画 Native application in Logto (customData.key manga-app).
    static let clientId = "gdjqw40f0zh1o9u4rjtwn"
    /// Access tokens for this audience open the manga sync service only.
    static let resource = "https://manga.monogatari.top/sync"
    static let syncBase = "https://manga.monogatari.top"
    /// The bundle id lowercased: URL schemes compare case-insensitively and Logto stores this spelling.
    static let callbackScheme = "app.monogatari.monogatari"
    static let redirectUri = "\(callbackScheme)://callback"
    static let scopes = ["openid", "profile", "email", "offline_access"]
}

/// A failure the sign-in screens explain. `code` is Logto's error code or one of gate_rate, gate_pow,
/// gate_unavailable, network, session_lost, invalid_state, cancelled.
struct AuthFailure: Error, Equatable {
    let code: String
    var status: Int?
    var retryAfter: Int?
    /// Logto's password policy findings (`password_rejected.too_short`, ...).
    var passwordIssues: [String] = []
}

enum PKCE {
    static func random(_ bytes: Int) -> String {
        var data = Data(count: bytes)
        _ = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, bytes, $0.baseAddress!) }
        return base64Url(data)
    }

    static func base64Url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func challenge(_ verifier: String) -> String {
        base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func authorizeUrl(state: String, challenge: String, extra: [String: String] = [:]) -> URL {
        var components = URLComponents(string: "\(AccountConfig.issuer)/auth")!
        components.queryItems = [
            .init(name: "client_id", value: AccountConfig.clientId),
            .init(name: "redirect_uri", value: AccountConfig.redirectUri),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: AccountConfig.scopes.joined(separator: " ")),
            .init(name: "resource", value: AccountConfig.resource),
            .init(name: "state", value: state),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "prompt", value: "consent")
        ] + extra.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        // A "+" in a value would read as a space on the server.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    /// The code in a redirect back to the app, after checking it answers this request.
    static func authorizationCode(_ location: URL, state: String) throws -> String {
        let items = URLComponents(url: location, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard value("state") == state else { throw AuthFailure(code: "invalid_state") }
        if let error = value("error") { throw AuthFailure(code: error == "access_denied" ? "cancelled" : error) }
        guard let code = value("code"), !code.isEmpty else { throw AuthFailure(code: "session_lost") }
        return code
    }
}

/// The first decimal nonce whose SHA-256(salt + nonce) starts with `bits` zero bits. About 2^bits
/// hashes, so it runs on a background task.
func solveProofOfWork(salt: String, bits: Int, limit: Int = 100_000_000) throws -> String {
    let prefix = Array(salt.utf8)
    for nonce in 0..<limit {
        let digest = SHA256.hash(data: prefix + Array(String(nonce).utf8))
        if leadingZeroBits(digest, bits) { return String(nonce) }
    }
    throw AuthFailure(code: "gate_pow")
}

func leadingZeroBits<D: Sequence>(_ digest: D, _ bits: Int) -> Bool where D.Element == UInt8 {
    var need = bits
    for byte in digest {
        if need <= 0 { return true }
        if need >= 8 {
            if byte != 0 { return false }
            need -= 8
        } else {
            return byte >> (8 - need) == 0
        }
    }
    return true
}

/// Never follows redirects: each hop is read and followed by hand, and the last one names a custom scheme.
private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// One sign-in, registration or password reset. Its cookies live only in this object.
final class AuthInteraction: @unchecked Sendable {
    private let session: URLSession
    private let issuer: String
    private let origin: String
    private var cookies: [(String, String)] = []
    private(set) var codeVerifier = ""
    private var state = ""
    private var started = false

    init(issuer: String = AccountConfig.issuer, configuration: URLSessionConfiguration = .ephemeral) {
        self.issuer = issuer
        let url = URL(string: issuer)!
        self.origin = "\(url.scheme!)://\(url.host!)\(url.port.map { ":\($0)" } ?? "")"
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
    }

    /// Keeps every cookie by name, whatever its path (one host is involved; Logto's resume cookie is path-scoped).
    private func keepCookies(_ response: HTTPURLResponse, for url: URL) {
        let fields = response.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            if let key = entry.key as? String, let value = entry.value as? String { result[key] = value }
        }
        for cookie in HTTPCookie.cookies(withResponseHeaderFields: fields, for: url) {
            cookies.removeAll { $0.0 == cookie.name }
            // Logto clears a cookie by setting it empty with an expiry in the past.
            let expired = cookie.expiresDate.map { $0 < Date() } ?? false
            if !cookie.value.isEmpty && !expired { cookies.append((cookie.name, cookie.value)) }
        }
    }

    private func raw(_ method: String, _ url: URL, body: [String: Any]? = nil, headers: [String: String] = [:]) async throws -> (HTTPURLResponse, Data) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("zh-CN", forHTTPHeaderField: "Accept-Language")
        if !cookies.isEmpty { request.setValue(cookies.map { "\($0.0)=\($0.1)" }.joined(separator: "; "), forHTTPHeaderField: "Cookie") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw AuthFailure(code: "network") }
            keepCookies(http, for: url)
            return (http, data)
        } catch let failure as AuthFailure {
            throw failure
        } catch {
            throw AuthFailure(code: "network")
        }
    }

    static func failure(_ response: HTTPURLResponse, _ data: Data) -> AuthFailure {
        let status = response.statusCode
        if let gate = response.value(forHTTPHeaderField: "X-Gate-Reason") {
            let code = gate == "rate" ? "gate_rate" : gate == "unavailable" ? "gate_unavailable" : "gate_pow"
            return AuthFailure(code: code, status: status,
                               retryAfter: response.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) })
        }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let code = (json?["code"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if code == "session.not_found" { return AuthFailure(code: "session_lost", status: status) }
        let issues = (json?["data"] as? [[String: Any]])?.compactMap { $0["code"] as? String } ?? []
        return AuthFailure(code: code ?? (status == 429 ? "request.too_many_requests" : "http_\(status)"), status: status, passwordIssues: issues)
    }

    @discardableResult
    private func call(_ method: String, _ path: String, _ body: [String: Any]? = nil, headers: [String: String] = [:]) async throws -> [String: Any] {
        let (response, data) = try await raw(method, URL(string: origin + path)!, body: body, headers: headers)
        guard (200..<300).contains(response.statusCode) else { throw Self.failure(response, data) }
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:]
    }

    private func proofOfWork(_ email: String) async throws -> [String: String] {
        let challenge = try await call("GET", "/gate/challenge")
        guard let salt = challenge["salt"] as? String, let bits = (challenge["bits"] as? NSNumber)?.intValue,
              let id = challenge["challenge"] as? String else { throw AuthFailure(code: "gate_pow") }
        let nonce = try await Task.detached(priority: .userInitiated) { try solveProofOfWork(salt: salt, bits: bits) }.value
        return ["X-Mo-Pow": "\(id):\(nonce)", "X-Mo-Email": email]
    }

    private func email(_ value: String) -> [String: Any] { ["type": "email", "value": value] }

    /// Opens the authorization request; Logto answers with the interaction cookies.
    func start(_ event: String) async throws {
        codeVerifier = PKCE.random(48)
        state = PKCE.random(24)
        let (response, _) = try await raw("GET", PKCE.authorizeUrl(state: state, challenge: PKCE.challenge(codeVerifier)))
        guard response.statusCode < 400, cookies.contains(where: { $0.0 == "_interaction" }) else {
            throw AuthFailure(code: "network", status: response.statusCode)
        }
        try await call("PUT", "/api/experience", ["interactionEvent": event])
        started = true
    }

    func switchEvent(_ event: String) async throws {
        try await call("PUT", "/api/experience/interaction-event", ["interactionEvent": event])
    }

    func verifyPassword(email address: String, password: String) async throws -> String {
        let result = try await call("POST", "/api/experience/verification/password",
                                    ["identifier": email(address), "password": password], headers: try await proofOfWork(address))
        return result["verificationId"] as? String ?? ""
    }

    func sendCode(_ event: String, email address: String) async throws -> String {
        let result = try await call("POST", "/api/experience/verification/verification-code",
                                    ["interactionEvent": event, "identifier": email(address)], headers: try await proofOfWork(address))
        return result["verificationId"] as? String ?? ""
    }

    func verifyCode(email address: String, verification: String, code: String) async throws -> String {
        let result = try await call("POST", "/api/experience/verification/verification-code/verify",
                                    ["identifier": email(address), "verificationId": verification, "code": code])
        return result["verificationId"] as? String ?? ""
    }

    func identify(_ verification: String? = nil) async throws {
        try await call("POST", "/api/experience/identification", verification.map { ["verificationId": $0] } ?? [:])
    }

    func addEmail(_ verification: String) async throws {
        try await call("POST", "/api/experience/profile", ["type": "email", "verificationId": verification])
    }

    func setPassword(_ password: String) async throws {
        try await call("POST", "/api/experience/profile", ["type": "password", "value": password])
    }

    func resetPassword(_ password: String) async throws {
        try await call("PUT", "/api/experience/profile/password", ["password": password])
    }

    /// Completes the interaction and returns the authorization code for this app.
    func submit() async throws -> String {
        precondition(started, "Interaction not started")
        var location = (try await call("POST", "/api/experience/submit"))["redirectTo"] as? String
        // Logto walks through its own pages (consent) before it returns to the app.
        var hop = 0
        while hop < 8, let current = location, !current.hasPrefix(AccountConfig.redirectUri) {
            let url = URL(string: current).flatMap { $0.scheme == nil ? nil : $0 } ?? URL(string: current, relativeTo: URL(string: issuer + "/"))!.absoluteURL
            let (response, _) = try await raw("GET", url)
            location = response.value(forHTTPHeaderField: "Location").map { next in
                URL(string: next).flatMap { $0.scheme == nil ? nil : $0.absoluteString } ?? URL(string: next, relativeTo: url)!.absoluteString
            }
            hop += 1
        }
        guard let final = location, final.hasPrefix(AccountConfig.redirectUri), let url = URL(string: final) else {
            throw AuthFailure(code: "session_lost")
        }
        return try PKCE.authorizationCode(url, state: state)
    }
}
