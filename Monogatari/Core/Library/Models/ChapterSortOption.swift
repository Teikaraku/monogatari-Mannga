//
//  ChapterSortOption.swift
//  Monogatari
//
//  Created by Skitty on 2/14/24.
//

import Foundation
import AidokuRunner

enum ChapterSortOption: Int, CaseIterable {
    case sourceOrder = 0
    case chapter
    case uploadDate

    init(flags: Int) {
        let option = (flags & ChapterFlagMask.sortMethod) >> 1
        self = ChapterSortOption(rawValue: option) ?? .sourceOrder
    }

    var stringValue: String {
        switch self {
            case .sourceOrder: NSLocalizedString("SOURCE_ORDER")
            case .chapter: NSLocalizedString("CHAPTER")
            case .uploadDate: NSLocalizedString("UPLOAD_DATE")
        }
    }

    /// Stable ties keep the source order; volumes are a separate numeric sequence from chapters.
    func sorted(_ chapters: [AidokuRunner.Chapter], ascending: Bool) -> [AidokuRunner.Chapter] {
        if self == .sourceOrder { return ascending ? chapters.reversed() : chapters }
        return chapters.enumerated().sorted { lhs, rhs in
            let comparison: ComparisonResult
            if self == .uploadDate {
                comparison = (lhs.element.dateUploaded ?? .distantPast).compare(rhs.element.dateUploaded ?? .distantPast)
            } else {
                comparison = Self.compareNumbers(lhs.element, rhs.element)
            }
            if comparison == .orderedSame { return ascending ? lhs.offset > rhs.offset : lhs.offset < rhs.offset }
            return ascending ? comparison == .orderedAscending : comparison == .orderedDescending
        }.map(\.element)
    }

    private static func compareNumbers(_ lhs: AidokuRunner.Chapter, _ rhs: AidokuRunner.Chapter) -> ComparisonResult {
        func compare(_ a: Float, _ b: Float) -> ComparisonResult {
            a < b ? .orderedAscending : a > b ? .orderedDescending : .orderedSame
        }
        let chapterComparison = compare(lhs.chapterNumber ?? -1, rhs.chapterNumber ?? -1)
        if chapterComparison != .orderedSame { return chapterComparison }
        if lhs.chapterNumber == nil, rhs.chapterNumber == nil, lhs.volumeNumber != nil, rhs.volumeNumber != nil {
            func series(_ title: String?) -> String {
                let title = title ?? ""
                guard let range = title.range(of: #"(?:第\s*)?(?:[0-9]+(?:\.[0-9]+)?|[〇零一二两兩三四五六七八九十百千万萬]+)\s*[卷巻册冊]"#, options: .regularExpression) else { return "" }
                return String(title[..<range.lowerBound]).trimmingCharacters(in: .whitespaces).lowercased()
            }
            let a = series(lhs.title), b = series(rhs.title)
            if a != b { return a < b ? .orderedAscending : .orderedDescending }
        }
        return compare(lhs.volumeNumber ?? -1, rhs.volumeNumber ?? -1)
    }
}
