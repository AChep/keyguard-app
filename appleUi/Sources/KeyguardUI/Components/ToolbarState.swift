import Foundation
import KeyguardShared

#if os(macOS)
struct FilterToolbarState: Equatable {
    let filters: [VaultFilterItemSnapshot]
    let canClearFilters: Bool
    let activeFilterCount: Int
    private let fingerprint: [String]

    @MainActor static let empty = FilterToolbarState(
        filters: [],
        canClearFilters: false,
        activeFilterCount: 0
    )

    init(snapshot: WatchtowerSnapshot) {
        self.init(
            filters: snapshot.filters,
            canClearFilters: snapshot.canClearFilters,
            activeFilterCount: Int(snapshot.activeFilterCount)
        )
    }

    private init(
        filters: [VaultFilterItemSnapshot],
        canClearFilters: Bool,
        activeFilterCount: Int
    ) {
        self.filters = filters
        self.canClearFilters = canClearFilters
        self.activeFilterCount = activeFilterCount
        self.fingerprint =
            [
                "clear:\(canClearFilters)",
                "count:\(activeFilterCount)",
            ] + filters.map(filterFingerprint)
    }

    static func == (lhs: FilterToolbarState, rhs: FilterToolbarState) -> Bool {
        lhs.fingerprint == rhs.fingerprint
    }
}

struct ListActionsToolbarState: Equatable {
    let actions: [VaultActionSnapshot]
    private let fingerprint: [String]

    @MainActor static let empty = ListActionsToolbarState(actions: [])

    init(actions: [VaultActionSnapshot]) {
        self.actions = actions
        self.fingerprint = actions.map(actionFingerprint)
    }

    static func == (lhs: ListActionsToolbarState, rhs: ListActionsToolbarState) -> Bool {
        lhs.fingerprint == rhs.fingerprint
    }
}

struct WatchtowerOptionsToolbarState: Equatable {
    let options: [WatchtowerOptionSnapshot]
    private let fingerprint: [String]

    @MainActor static let empty = WatchtowerOptionsToolbarState(options: [])

    init(snapshot: WatchtowerSnapshot) {
        options = snapshot.options
        fingerprint = snapshot.options.map { "\($0.id)|\($0.title)" }
    }

    private init(options: [WatchtowerOptionSnapshot]) {
        self.options = options
        self.fingerprint = options.map { "\($0.id)|\($0.title)" }
    }

    static func == (lhs: WatchtowerOptionsToolbarState, rhs: WatchtowerOptionsToolbarState) -> Bool {
        lhs.fingerprint == rhs.fingerprint
    }
}

struct GeneratorOptionsToolbarState: Equatable {
    let options: [GeneratorActionSnapshot]
    let canOpenHistory: Bool
    private let fingerprint: [String]

    @MainActor static let empty = GeneratorOptionsToolbarState(options: [])

    init(snapshot: GeneratorSnapshot) {
        options = snapshot.options
        canOpenHistory = snapshot.canOpenHistory
        fingerprint = snapshot.options.map { "\($0.id)|\($0.title)|\($0.selected)" }
    }

    private init(options: [GeneratorActionSnapshot]) {
        self.options = options
        self.canOpenHistory = false
        self.fingerprint = options.map { "\($0.id)|\($0.title)|\($0.selected)" }
    }

    static func == (lhs: GeneratorOptionsToolbarState, rhs: GeneratorOptionsToolbarState) -> Bool {
        lhs.canOpenHistory == rhs.canOpenHistory && lhs.fingerprint == rhs.fingerprint
    }
}

private func filterFingerprint(_ item: VaultFilterItemSnapshot) -> String {
    [
        item.id,
        item.title,
        String(describing: item.kind),
        "\(item.checked)",
        "\(item.enabled)",
    ].joined(separator: "|")
}

private func actionFingerprint(_ action: VaultActionSnapshot) -> String {
    [
        action.id,
        action.title,
        "\(action.startsSection)",
        "\(action.switchState?.boolValue.description ?? "nil")",
    ].joined(separator: "|")
}
#endif
