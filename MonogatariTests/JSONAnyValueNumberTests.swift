import Foundation
import Testing
@testable import Monogatari

struct JSONAnyValueNumberTests {
    @Test func preservesDoublePrecision() throws {
        let value = try JSONDecoder().decode(JSONAnyValue.self, from: Data("0.123456789012345".utf8))
        #expect(value.doubleValue == 0.123456789012345)
        #expect(value.intValue == 0)
    }

    @Test func largeFloatingValuesDoNotTrap() throws {
        let value = try JSONDecoder().decode(JSONAnyValue.self, from: Data("1e100".utf8))
        #expect(value.doubleValue == 1e100)
        #expect(value.intValue == nil)
        #expect(JSONAnyValue.double(.infinity).intValue == nil)
        #expect(JSONAnyValue.double(.nan).intValue == nil)
        #expect(JSONAnyValue.double(Double(Int.max)).intValue == nil)
    }
}
