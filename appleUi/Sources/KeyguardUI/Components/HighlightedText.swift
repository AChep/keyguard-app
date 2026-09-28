import Foundation

/// Kotlin string spans use UTF-16 offsets, including for emoji and combining marks.
func highlightedText(_ title: String, utf16Ranges: [Range<Int>]) -> AttributedString {
    var result = AttributedString(title)
    guard !utf16Ranges.isEmpty else { return result }
    let count = title.utf16.count
    for range in utf16Ranges {
        guard range.lowerBound >= 0, range.upperBound <= count,
            let stringRange = Range(NSRange(location: range.lowerBound, length: range.count), in: title),
            let from = AttributedString.Index(stringRange.lowerBound, within: result),
            let to = AttributedString.Index(stringRange.upperBound, within: result)
        else { continue }
        result[from..<to].inlinePresentationIntent = .stronglyEmphasized
    }
    return result
}
