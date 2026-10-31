//
//  AccountApis.swift
//  Monogatari
//
//  The identity center's self-service Account API (profile, password, email, linked accounts) and this
//  app's sync service (records, devices, avatar). Mirrors MyAccountApi.kt and SyncApi.kt.
//

import Foundation
import UIKit

struct AccountProfile: Equatable {
    let id: String
    var name: String?
    var avatar: String?
    var email: String?
    var hasPassword: Bool
    var createdAt: Date?
    /// Linked social accounts by provider (google, github), with the account's email or name.
    var identities: [String: String]

    init(json: [String: Any]) {
        func text(_ key: String) -> String? { (json[key] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        id = json["id"] as? String ?? ""
        name = text("name"); avatar = text("avatar"); email = text("primaryEmail")
        hasPassword = json["hasPassword"] as? Bool ?? false
        createdAt = (json["createdAt"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
        var linked: [String: String] = [:]
        for (target, value) in json["identities"] as? [String: Any] ?? [:] {
            let details = (value as? [String: Any])?["details"] as? [String: Any]
            linked[target] = details?["email"] as? String ?? details?["name"] as? String ?? ""
        }
        identities = linked
    }
}

struct MyAccountApi {
    var origin = AccountConfig.origin
    var token: (Bool) async throws -> String = { try await AccountService.shared.accountApiToken(forceRefresh: $0) }
    var session = URLSession.shared

    @discardableResult
    private func call(_ method: String, _ path: String, _ body: [String: Any?]? = nil, verification: String? = nil) async throws -> [String: Any] {
        for attempt in 0..<2 {
            var request = URLRequest(url: URL(string: origin + path)!)
            request.httpMethod = method
            request.setValue("Bearer \(try await token(attempt > 0))", forHTTPHeaderField: "Authorization")
            request.setValue("zh-CN", forHTTPHeaderField: "Accept-Language")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            if let verification { request.setValue(verification, forHTTPHeaderField: "logto-verification-id") }
            if let body {
                request.httpBody = try JSONSerialization.data(withJSONObject: body.mapValues { $0 ?? NSNull() } as [String: Any])
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            let (data, response): (Data, URLResponse)
            do { (data, response) = try await session.data(for: request) } catch { throw AuthFailure(code: "network") }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401 && attempt == 0 { continue }
            let json = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:]
            if (200..<300).contains(status) { return json }
            let issues = (json["data"] as? [[String: Any]])?.compactMap { $0["code"] as? String } ?? []
            throw AuthFailure(code: (json["code"] as? String) ?? "http_\(status)", status: status, passwordIssues: issues)
        }
        throw AuthFailure(code: "auth.unauthorized", status: 401)
    }

    func profile() async throws -> AccountProfile { AccountProfile(json: try await call("GET", "/api/my-account")) }

    /// Name and avatar change without re-verifying. An empty avatar removes it.
    func update(name: String? = nil, avatar: String? = nil) async throws -> AccountProfile {
        var body: [String: Any?] = [:]
        if let name { body["name"] = name }
        // null removes the avatar.
        if let avatar { body["avatar"] = avatar.isEmpty ? NSNull() : avatar }
        return AccountProfile(json: try await call("PATCH", "/api/my-account", body))
    }

    func verifyPassword(_ password: String) async throws -> String {
        try await call("POST", "/api/verifications/password", ["password": password])["verificationRecordId"] as? String ?? ""
    }

    func sendCode(to email: String) async throws -> String {
        try await call("POST", "/api/verifications/verification-code", ["identifier": ["type": "email", "value": email]])["verificationRecordId"] as? String ?? ""
    }

    func confirmCode(email: String, verification: String, code: String) async throws -> String {
        try await call("POST", "/api/verifications/verification-code/verify", [
            "identifier": ["type": "email", "value": email], "verificationId": verification, "code": code
        ])["verificationRecordId"] as? String ?? ""
    }

    func changePassword(_ password: String, verification: String) async throws {
        try await call("POST", "/api/my-account/password", ["password": password], verification: verification)
    }

    /// Replaces the email: `verification` proves the user, `newEmailVerification` the new address.
    func changeEmail(_ email: String, verification: String, newEmailVerification: String) async throws {
        try await call("POST", "/api/my-account/primary-email", ["email": email, "newIdentifierVerificationRecordId": newEmailVerification],
                       verification: verification)
    }

    func unlinkSocial(_ target: String, verification: String) async throws {
        try await call("DELETE", "/api/my-account/identities/\(target)", verification: verification)
    }
}

// MARK: - Sync service

struct SyncRecord: Equatable {
    let kind: String
    let key: String
    /// Milliseconds since the epoch; the newer write wins on every device.
    let updated: Int64
    var data: [String: Any]?
    var deleted = false

    var json: [String: Any] {
        var value: [String: Any] = ["kind": kind, "key": key, "updated": updated, "deleted": deleted]
        if !deleted { value["data"] = data ?? [:] }
        return value
    }

    init(kind: String, key: String, updated: Int64, data: [String: Any]? = nil, deleted: Bool = false) {
        self.kind = kind; self.key = key; self.updated = updated; self.data = data; self.deleted = deleted
    }

    init?(json: [String: Any]) {
        guard let kind = json["kind"] as? String, let key = json["key"] as? String, let updated = (json["updated"] as? NSNumber)?.int64Value else { return nil }
        let deleted = json["deleted"] as? Bool ?? false
        self.init(kind: kind, key: key, updated: updated, data: deleted ? nil : json["data"] as? [String: Any], deleted: deleted)
    }

    static func == (lhs: SyncRecord, rhs: SyncRecord) -> Bool {
        lhs.kind == rhs.kind && lhs.key == rhs.key && lhs.updated == rhs.updated && lhs.deleted == rhs.deleted
            && contentHash(lhs.data) == contentHash(rhs.data)
    }
}

struct SyncPage {
    let items: [SyncRecord]
    let cursor: Int64
    let more: Bool
    let reset: Bool
    let seq: Int64?
}

struct SyncApiError: Error, Equatable {
    /// unauthorized, device_revoked, quota_exceeded, network, server
    let code: String
    var status: Int?
}

struct AccountDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String
    let lastSeen: Date
    let current: Bool
}

struct SyncApi {
    var baseUrl = AccountConfig.syncBase
    var token: (Bool) async throws -> String = { try await AccountService.shared.accessToken(forceRefresh: $0) }
    var session = URLSession.shared
    static let pageSize = 1000

    /// This install on the account's device list: a random id kept for the install, the model, the platform.
    static var deviceHeaders: [String: String] {
        let defaults = UserDefaults.standard
        var id = defaults.string(forKey: "Data.accountDevice") ?? ""
        if id.count < 8 {
            id = (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
            defaults.set(id, forKey: "Data.accountDevice")
        }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return ["X-Mo-Device": id, "X-Mo-Device-Name": deviceName.addingPercentEncoding(withAllowedCharacters: allowed) ?? "iOS", "X-Mo-Platform": "ios"]
    }

    /// "iPhone16,2" style identifiers are not what people call their phone; the system's model name is.
    static var deviceName: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let model = UIDevice.current.model
        return machine.isEmpty ? model : "\(model) (\(machine))"
    }

    private func send(_ method: String, _ path: String, body: Data? = nil, contentType: String? = nil) async throws -> Data {
        for attempt in 0..<2 {
            var request = URLRequest(url: URL(string: baseUrl + path)!)
            request.httpMethod = method
            request.timeoutInterval = 30
            request.setValue("Bearer \(try await token(attempt > 0))", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            for (key, value) in Self.deviceHeaders { request.setValue(value, forHTTPHeaderField: key) }
            if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
            request.httpBody = body
            let (data, response): (Data, URLResponse)
            do { (data, response) = try await session.data(for: request) } catch { throw SyncApiError(code: "network") }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (200..<300).contains(status) { return data }
            let code = (((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? [String: Any])?["code"] as? String
            // Removed from the device list elsewhere: a new token would not help.
            if status == 401 && code == "device_revoked" { throw SyncApiError(code: "device_revoked", status: 401) }
            if status == 401 && attempt == 0 { continue }
            if status == 401 { throw SyncApiError(code: "unauthorized", status: 401) }
            if status == 507 { throw SyncApiError(code: "quota_exceeded", status: 507) }
            throw SyncApiError(code: "server", status: status)
        }
        throw SyncApiError(code: "unauthorized", status: 401)
    }

    private func object(_ data: Data) -> [String: Any] { ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:] }

    func pull(since: Int64) async throws -> SyncPage {
        let body = object(try await send("GET", "/sync/v1/changes?since=\(since)&limit=\(Self.pageSize)"))
        return SyncPage(items: (body["items"] as? [[String: Any]] ?? []).compactMap(SyncRecord.init(json:)),
                        cursor: (body["cursor"] as? NSNumber)?.int64Value ?? since, more: body["more"] as? Bool ?? false,
                        reset: body["reset"] as? Bool ?? false, seq: (body["seq"] as? NSNumber)?.int64Value)
    }

    /// Sends `records`; returns the account's sequence after the write.
    func push(_ records: [SyncRecord]) async throws -> Int64? {
        guard !records.isEmpty else { return nil }
        let body = try JSONSerialization.data(withJSONObject: ["items": records.map(\.json)])
        return (object(try await send("POST", "/sync/v1/changes", body: body, contentType: "application/json"))["seq"] as? NSNumber)?.int64Value
    }

    func devices() async throws -> [AccountDevice] {
        (object(try await send("GET", "/sync/v1/devices"))["items"] as? [[String: Any]] ?? []).compactMap { item in
            guard let id = item["id"] as? String else { return nil }
            let raw = item["name"] as? String ?? ""
            return AccountDevice(id: id, name: raw.removingPercentEncoding ?? raw, platform: item["platform"] as? String ?? "other",
                                 lastSeen: Date(timeIntervalSince1970: ((item["seen"] as? NSNumber)?.doubleValue ?? 0) / 1000),
                                 current: item["current"] as? Bool ?? false)
        }
    }

    /// Signs `id` out: its next request is refused and the app there signs out.
    func removeDevice(_ id: String) async throws {
        _ = try await send("DELETE", "/sync/v1/devices/\(id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? id)")
    }

    /// The server re-encodes the picture to a square WebP and returns its URL.
    func uploadAvatar(_ data: Data) async throws -> String {
        object(try await send("POST", "/sync/v1/avatar", body: data, contentType: "application/octet-stream"))["url"] as? String ?? ""
    }

    /// Deletes everything this app synced to the account, and its device list.
    func erase() async throws { _ = try await send("DELETE", "/sync/v1/account") }
}
