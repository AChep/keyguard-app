/// Keeps prefetched actions associated with the row version that requested them.
struct VaultRowActionsLoad {
    let request: VaultRowContextMenuRequest
    let generation: Int
    private var resolvedActions: [VaultAction]?

    init(request: VaultRowContextMenuRequest, generation: Int) {
        self.request = request
        self.generation = generation
    }

    func actions(for request: VaultRowContextMenuRequest) -> [VaultAction] {
        self.request == request ? resolvedActions ?? [] : []
    }

    /// A completion from a recycled cell or an earlier task cannot replace the
    /// current request, including when a task retries the very same row version.
    func resolving(_ actions: [VaultAction], for load: Self) -> Self {
        guard request == load.request, generation == load.generation else { return self }
        var resolved = self
        resolved.resolvedActions = actions
        return resolved
    }
}
