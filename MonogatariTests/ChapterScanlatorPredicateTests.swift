import Foundation
import Testing
@testable import Monogatari

struct ChapterScanlatorPredicateTests {
    private func matches(_ value: String?, selected: [String]?) -> Bool {
        guard let predicate = CoreDataManager.chapterScanlatorPredicate(selected) else { return true }
        return predicate.evaluate(with: ["scanlator": value.map { $0 as Any } ?? NSNull()])
    }

    @Test func matchesEachGroupAtEveryTokenPosition() {
        #expect(matches("Group A", selected: ["Group A"]))
        #expect(matches("Group A, Group B", selected: ["Group A"]))
        #expect(matches("Group A, Group B", selected: ["Group B"]))
        #expect(matches("Group A, Group B, Group C", selected: ["Group B"]))
        #expect(!matches("Group AA, Group BB", selected: ["Group A"]))
        #expect(!matches("Other Group A", selected: ["Group A"]))
    }

    @Test func treatsPredicateCharactersAsLiteralNames() {
        #expect(matches("Other, A*?'%_", selected: ["A*?'%_"]))
        #expect(!matches("Other, Actual", selected: ["A*"]))
        #expect(matches("组一, 组二", selected: ["组二"]))
    }

    @Test func keepsAnonymousAndEmptyFilterSemantics() {
        #expect(matches(nil, selected: [""]))
        #expect(matches("", selected: [""]))
        #expect(!matches(nil, selected: ["Group A"]))
        #expect(matches("Group A", selected: []))
        #expect(matches(nil, selected: nil))
    }
}
