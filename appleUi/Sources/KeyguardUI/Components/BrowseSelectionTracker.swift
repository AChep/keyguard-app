import SwiftUI

/// An explicit deep link may have no list row. Only list-originated selections,
/// or deep links previously matched to a row, are dismissed by completed results.
struct BrowseSelectionTracker {
    private var matchedDetailId: Int64?

    mutating func shouldClear(detailId: Int64?, fromList: Bool, isPresent: Bool, isLoaded: Bool) -> Bool {
        guard let detailId else {
            matchedDetailId = nil
            return false
        }
        guard isLoaded else { return false }
        if isPresent {
            matchedDetailId = detailId
            return false
        }
        guard fromList || matchedDetailId == detailId else { return false }
        matchedDetailId = nil
        return true
    }
}

/// The list's view of its browse detail; a nil `detailId` means none is open.
struct BrowseSelectionInput: Equatable {
    var detailId: Int64?
    var fromList = false
    var isPresent = false
    var isLoaded = false
}

extension View {
    /// Clears the browse detail once a completed list no longer contains its row.
    func clearsMissingBrowseSelection(_ input: BrowseSelectionInput, clear: @escaping () -> Void) -> some View {
        modifier(BrowseSelectionReconciler(input: input, clear: clear))
    }
}

private struct BrowseSelectionReconciler: ViewModifier {
    let input: BrowseSelectionInput
    let clear: () -> Void

    @State private var tracker = BrowseSelectionTracker()

    func body(content: Content) -> some View {
        content.onChange(of: input, initial: true) { _, input in
            if tracker.shouldClear(
                detailId: input.detailId, fromList: input.fromList,
                isPresent: input.isPresent, isLoaded: input.isLoaded)
            {
                clear()
            }
        }
    }
}
