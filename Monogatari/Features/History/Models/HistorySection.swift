//
//  HistorySection.swift
//  Monogatari
//
//  Created by Skitty on 7/31/25.
//

import Foundation

struct HistorySection: Hashable {
    let daysAgo: Int
    var entries: [HistoryEntry]
}
