/// A reused row or a changed content fingerprint invalidates prefetched actions.
struct VaultRowContextMenuRequest: Equatable {
    let rowId: String
    let revision: Int64?
}
