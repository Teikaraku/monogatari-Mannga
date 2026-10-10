import Foundation
import Testing
import ImageIO
@testable import Monogatari

/// Opt-in network evidence from Apple's real URLSession, separate from deterministic fixtures.
struct CopyLiveNetworkTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["COPY_LIVE_SERVICE"] == "1"))
    func nativeSessionReadsHomepageAndChapterImages() async throws {
        let session = CopyClient.networkSession
        let (traceData, _) = try await session.data(from: URL(string: "https://www.cloudflare.com/cdn-cgi/trace")!)
        let trace = String(decoding: traceData, as: UTF8.self).split(separator: "\n").filter { $0.hasPrefix("ip=") || $0.hasPrefix("loc=") }.joined(separator: " ")
        let client = CopyClient()
        let home = try await client.home()
        print("COPY_LIVE homepage: \(trace); features=\(home.features.count); sections=\(home.sections.count); notice=\(home.notice ?? "none"); diagnostics=\(home.diagnostics ?? "none")")
        #expect(!home.sections.isEmpty && !home.features.isEmpty)
        let search = try await client.catalog(CopyQuery(kind: "search", keyword: "金牌得主"), page: 1)
        #expect(!search.entries.isEmpty)
        let chapters = try await client.chapters(key: "chubaowangnv")
        #expect(chapters.count == 41)
        let expected = (1...18).reversed().map { String(format: "D%02d卷", $0) } + (1...18).reversed().map { String(format: "第%02d卷", $0) }
        #expect(chapters.filter { $0.volumeNumber != nil }.compactMap(\.title) == expected)
        let pages = try await client.pages(key: "modujingbingdenuli", chapter: "7b302219-c084-11f1-a40f-fa163e02432f")
        #expect(pages.count == 21)
        for address in [pages.first!, pages.last!] {
            let (data, response) = try await session.data(for: client.imageRequest(URL(string: address)!))
            #expect((response as? HTTPURLResponse)?.statusCode == 200)
            #expect(!data.isEmpty)
            let image = CGImageSourceCreateWithData(data as CFData, nil)
            #expect(image != nil && CGImageSourceGetCount(image!) > 0)
            print("COPY_LIVE reader image: bytes=\(data.count), type=\(response.mimeType ?? "unknown")")
        }
    }
}
