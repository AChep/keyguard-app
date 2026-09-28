/// Carries a detail snapshot and the entity observed by its producer together.
/// The identity changes only with an accepted snapshot, never with UI selection.
struct ObservedDetail<Snapshot, Identity: Hashable> {
    let snapshot: Snapshot
    let identity: Identity?

    init(snapshot: Snapshot, identity: Identity? = nil) {
        self.snapshot = snapshot
        self.identity = identity
    }
}
