import Foundation

/// Commands captured for one presentation; queued UI callbacks cannot act on its replacement.
struct SendListActions: Sendable {
    let setQuery: @MainActor @Sendable (String) -> Void
    let invokeFilter: @MainActor @Sendable (String) -> Void
    let invokeSort: @MainActor @Sendable (String) -> Void
    let clearFilters: @MainActor @Sendable () -> Void
    let clearSort: @MainActor @Sendable () -> Void
    let toggleSelection: @MainActor @Sendable (String) -> Void
    let invokeSelectionAction: @MainActor @Sendable (String) -> Void
    let invokeAction: @MainActor @Sendable (String) -> Void
    let clearSelection: @MainActor @Sendable () -> Void
    let dropFile: @MainActor @Sendable (URL) -> Void
}
