import Foundation

/// Caches only structure. Selection and row-content updates do not walk entries.
struct VaultListProjection {
    struct Options: Equatable {
        var includesQuickFilters = true
        var usesNativeEmptyState = false
        var leadingId: String? = nil
    }
    struct Changes {
        let entriesChanged: Bool
        let idsChanged: Bool
        var needsReconfiguration: Bool { entriesChanged || idsChanged }
    }
    private struct Key: Equatable {
        let revision: Int64
        let options: Options
    }
    private var key: Key?
    private(set) var entries: [VaultRowEntry] = []
    private(set) var entriesById: [String: VaultRowEntry] = [:]
    private(set) var ids: [String] = []

    /// An empty structure can be an initial/reset frame. Only a published marker
    /// confirms emptiness; section and quick-filter rows do not count as items.
    static func hasNoItems(in entries: [VaultRowEntry]) -> Bool {
        !entries.contains { $0.kind == .item } && entries.contains { $0.kind == .noItems }
    }

    mutating func update(entries source: [VaultRowEntry], revision: Int64, options: Options) -> Changes {
        let nextKey = Key(revision: revision, options: options)
        guard key != nextKey else { return Changes(entriesChanged: false, idsChanged: false) }
        let previousKey = key
        key = nextKey
        let hidesNoItems = options.usesNativeEmptyState && Self.hasNoItems(in: source)
        let projected =
            options.includesQuickFilters && !hidesNoItems
            ? source
            : source.filter {
                (options.includesQuickFilters || $0.kind != .quickFilters)
                    && (!hidesNoItems || $0.kind != .noItems)
            }
        let entriesChanged = projected != entries
        guard entriesChanged || previousKey?.options.leadingId != options.leadingId else {
            return Changes(entriesChanged: false, idsChanged: false)
        }
        var nextIds = projected.map(\.id)
        if let leadingId = options.leadingId { nextIds.insert(leadingId, at: 0) }
        let idsChanged = nextIds != ids
        if entriesChanged {
            entries = projected
            entriesById = Dictionary(projected.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        }
        if idsChanged { ids = nextIds }
        return Changes(entriesChanged: entriesChanged, idsChanged: idsChanged)
    }
}
