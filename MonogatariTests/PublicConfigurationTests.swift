import Foundation
import Testing

@testable import Monogatari

@Suite struct PublicConfigurationTests {
    @Test func updateFeedUsesOnlyGitHub() {
        #expect(AppUpdateFeed.addresses.map(\.absoluteString) == [
            "https://github.com/Teikaraku/monogatari-Mannga/releases/latest/download/latest.json"
        ])
    }

    private let githubDownload = "https://github.com/Teikaraku/monogatari-Mannga/releases/download/v0.9.3/Monogatari-0.9.3.ipa"
    private let externalDownload = "https://updates.example.invalid/releases/v0.9.3/reader.ipa"

    private func release(_ primary: String, mirrors: [String] = []) -> AppRelease? {
        AppRelease(["version": "0.9.3", "build": 6, "url": primary, "mirrors": mirrors])
    }

    @Test func githubPrimaryIgnoresExternalMirrors() {
        #expect(release(githubDownload, mirrors: [externalDownload, githubDownload])?.urls.map(\.absoluteString) == [githubDownload])
    }

    @Test func externalPrimaryStillKeepsAValidGithubMirror() {
        #expect(release(externalDownload, mirrors: [githubDownload])?.urls.map(\.absoluteString) == [githubDownload])
    }

    @Test func allExternalCandidatesProduceNoRelease() {
        #expect(release(externalDownload, mirrors: ["https://another.example.invalid/reader.ipa"]) == nil)
    }

    @Test func currentAndHistoricalRepositoryNamesAreCaseInsensitive() {
        for repository in ["monogatari-Mannga", "monogatari-mannga", "MONOGATARI-MANNGA", "monogatari-reader", "MONOGATARI-READER"] {
            let url = githubDownload.replacingOccurrences(of: "Teikaraku/monogatari-Mannga", with: "TEIKARAKU/\(repository)")
            #expect(release(url)?.urls.map(\.absoluteString) == [url])
        }
    }

    @Test func malformedAuthoritiesAndOtherRepositoriesAreRejected() {
        let replacements = [
            ("https://", "http://"), ("github.com", "github.com.evil.invalid"),
            ("github.com", "user@github.com"), ("github.com", "@github.com"),
            ("github.com", "user:password@github.com"), ("github.com", "github.com:8443"),
            ("Teikaraku", "OtherOwner"), ("monogatari-Mannga", "another-repo"),
            ("monogatari-Mannga", "monogatari-reader-other"), ("monogatari-Mannga", "monogatari-Mannga-other"),
            ("releases/download", "releases/latest/download"),
            ("v0.9.3/Monogatari-0.9.3.ipa", "v0.9.3/"),
            ("v0.9.3/Monogatari-0.9.3.ipa", "../reader.ipa"),
            ("v0.9.3/Monogatari-0.9.3.ipa", "%2e%2e/reader.ipa"),
            ("Monogatari-0.9.3.ipa", "bad%2Freader.ipa"),
            ("Monogatari-0.9.3.ipa", "bad%5Creader.ipa")
        ]
        for (old, new) in replacements {
            #expect(release(githubDownload.replacingOccurrences(of: old, with: new)) == nil)
        }
    }

    @Test func defaultHttpsPortAndEncodedAssetNamesAreAllowed() {
        let allowed = githubDownload.replacingOccurrences(of: "github.com", with: "github.com:443")
            .replacingOccurrences(of: "Monogatari-0.9.3.ipa", with: "%E7%89%A9%E8%AF%AD.ipa")
        #expect(release(allowed)?.urls.map(\.absoluteString) == [allowed])
    }

    @Test func missingAndUnexpandedCredentialsStayEmpty() {
        #expect(OAuthClient.applicationCredential("secret", info: nil).isEmpty)
        #expect(OAuthClient.applicationCredential("secret", info: [:]).isEmpty)
        #expect(OAuthClient.applicationCredential("secret", info: ["secret": "  \n"]).isEmpty)
        #expect(OAuthClient.applicationCredential("secret", info: ["secret": "$(PRIVATE_SETTING)"]).isEmpty)
        #expect(OAuthClient.applicationCredential("secret", info: ["secret": 123]).isEmpty)
        #expect(OAuthClient.applicationCredential("secret", info: ["secret": "fixture-secret"]) == "fixture-secret")
    }

    @Test func unconfiguredOAuthCannotStartAuthorization() async {
        let missingId = OAuthClient(id: "fixture", clientId: "", clientSecret: "fixture-secret", baseUrl: "https://example.invalid")
        let missingSecret = OAuthClient(id: "fixture", clientId: "fixture-id", clientSecret: "", baseUrl: "https://example.invalid")
        #expect(await missingId.getAuthenticationUrl() == nil)
        #expect(await missingSecret.getAuthenticationUrl() == nil)
    }

    @Test func publicPkceAndConfiguredOAuthStillCreateAuthorizationURLs() async {
        let publicClient = OAuthClient(id: "fixture", clientId: "fixture-id", baseUrl: "https://example.invalid")
        let configured = OAuthClient(id: "fixture", clientId: "fixture-id", clientSecret: "fixture-secret", baseUrl: "https://example.invalid")
        #expect(await publicClient.getAuthenticationUrl() != nil)
        #expect(await configured.getAuthenticationUrl() != nil)
    }

    @Test func unconfiguredTrackersDoNotExchangeOrRefreshTokens() async {
        let bangumi = BangumiApi(clientId: "", clientSecret: "")
        let shikimori = ShikimoriApi(clientId: "", clientSecret: "")
        #expect(await bangumi.getAccessToken(authCode: "fixture-code") == nil)
        #expect(await bangumi.refreshAccessToken() == nil)
        #expect(await shikimori.getAccessToken(authCode: "fixture-code") == nil)
        #expect(await shikimori.refreshAccessToken() == nil)
    }
}
