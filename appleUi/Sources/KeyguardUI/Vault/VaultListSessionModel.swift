import Foundation
import Observation
import KeyguardShared

/// Owns one Kotlin vault-list session and publishes converted Swift state.
/// List deltas are applied on the main actor in delivery order.
@MainActor
@Observable
final class VaultListSessionModel {
    /// Row content + structure; the list and its cells observe this, never
    /// the bridged snapshots.
    let store = VaultRowStore()

    private(set) var header: VaultHeader = .empty
    var isLoaded: Bool { header.loaded || header.needsAccount }
    /// The row of the detail this list opened, set by `VaultBrowseSelectionWatcher`.
    var browseSelectedRowId: String? = nil
    /// Header fields consumed by toolbar items. Kept separate so query/header churn
    /// doesn't invalidate toolbar buttons that only care about create availability.
    private(set) var createActions: [VaultAction] = []
    private(set) var needsAccount = false
    private(set) var filterCatalog: VaultFilterCatalog = .empty
    /// The cheap checked/enabled filter state, on its own channel so a filter
    /// toggle never re-delivers the catalog.
    private(set) var filterState: VaultFilterState = .empty
    private(set) var sortMenu: VaultSortMenu = .empty
    private(set) var toolbar: VaultToolbar = .empty
    /// Toolbar fields split by consumer so the sync indicator and overflow menu
    /// don't invalidate each other on unrelated changes.
    private(set) var toolbarActions: [VaultAction] = []
    private(set) var toolbarSyncing = false
    private(set) var selection: VaultSelection = .empty
    private(set) var totpStates: [String: TotpFieldSnapshot] = [:]

    @ObservationIgnored private let core: KeyguardCore?
    @ObservationIgnored private let config: VaultListSessionConfig
    @ObservationIgnored private let externalSession: VaultListSession?
    @ObservationIgnored private var session: VaultListSession?
    @ObservationIgnored private var navigationOrigin: ListNavigationOrigin?

    func setNavigationOrigin(_ origin: ListNavigationOrigin?) {
        navigationOrigin = origin
        session?.setNavigationOrigin(origin: origin)
    }

    /// Whether this model created its own session (and must therefore `close()` it in
    /// `stop()`). The main vault list does; a stacked list drives a provided session.
    private var ownsSession: Bool { externalSession == nil }
    @ObservationIgnored private var subscriptions: [KeyguardCancellable] = []
    /// The FIFO background→Main bridge for the delta channel.
    @ObservationIgnored private lazy var deltaPump = VaultDeltaPump(store: store)

    init(core: KeyguardCore, config: VaultListSessionConfig = .vaultMain()) {
        self.core = core
        self.config = config
        self.externalSession = nil
    }

    init(session: VaultListSession) {
        self.core = nil
        self.config = .vaultMain()
        self.externalSession = session
    }

    // MARK: - Lifecycle

    /// Creates the Kotlin session and subscribes all channels. Call on appear;
    /// balance with `stop()` on disappear. No-op while already started.
    func start() {
        guard session == nil else { return }

        let continuation = deltaPump.start()

        // Exactly one of `externalSession` (a stacked list) / `core` (the main list)
        // is set per init, so the `else` never fires in practice.
        guard let session = externalSession ?? core?.makeVaultListSession(config: config) else { return }
        self.session = session
        session.setNavigationOrigin(origin: navigationOrigin)

        // THE LIST. Background-delivered BY DESIGN: convert off-main, then one
        // ordered hop to Main through `deltaPump`. Do not touch any observable
        // state directly in this callback.
        subscriptions.append(
            session.observeListDelta { bridged in
                assert(
                    !Thread.isMainThread,
                    "observeListDelta must deliver off-main by design; converting on Main defeats the contract"
                )
                let delta = VaultDelta(bridged: bridged)
                continuation.yield(delta)
            })

        // THE SMALL CHANNELS. Main-delivered; convert (cheap) + assign
        // directly. `assumeIsolated` both documents and enforces the session's
        // delivery contract — it traps if a callback ever arrives off-main.
        subscriptions.append(
            session.observeHeader { [weak self] bridged in
                let value = VaultHeader(bridged: bridged)
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.header = value
                    self.createActions = value.createActions
                    self.needsAccount = value.needsAccount
                }
            })
        subscriptions.append(
            session.observeFilterCatalog { [weak self] bridged in
                let value = VaultFilterCatalog(bridged: bridged)
                MainActor.assumeIsolated { self?.filterCatalog = value }
            })
        subscriptions.append(
            session.observeFilterState { [weak self] bridged in
                let value = VaultFilterState(bridged: bridged)
                MainActor.assumeIsolated { self?.filterState = value }
            })
        subscriptions.append(
            session.observeSort { [weak self] bridged in
                let value = VaultSortMenu(bridged: bridged)
                MainActor.assumeIsolated { self?.sortMenu = value }
            })
        subscriptions.append(
            session.observeToolbar { [weak self] bridged in
                let value = VaultToolbar(bridged: bridged)
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.toolbar = value
                    self.toolbarActions = value.actions
                    self.toolbarSyncing = value.syncing
                }
            })
        subscriptions.append(
            session.observeSelection { [weak self] bridged in
                let value = VaultSelection(bridged: bridged)
                MainActor.assumeIsolated { self?.selection = value }
            })
        // TOTP stays bridged as-is: the map is small and `TotpBadgeCell` consumes
        // the snapshot directly.
        subscriptions.append(
            session.observeTotp { [weak self] states in
                MainActor.assumeIsolated { self?.totpStates = states }
            })
    }

    /// Cancels every channel, closes the Kotlin session and clears all state.
    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
        deltaPump.stop()
        // Only close a session we created; a provided (stacked-list) session is closed
        // Kotlin-side by its owning navigation-stack entry.
        if ownsSession {
            session?.close()
        }
        session = nil
        header = .empty
        browseSelectedRowId = nil
        createActions = []
        needsAccount = false
        filterCatalog = .empty
        filterState = .empty
        sortMenu = .empty
        toolbar = .empty
        toolbarActions = []
        toolbarSyncing = false
        selection = .empty
        totpStates = [:]
    }

    func setQuery(_ text: String) {
        session?.setQuery(text: text)
    }

    /// A programmatic clear — goes through the command path so the header's
    /// `queryRevision` bumps and the local `bridgedText` buffer adopts it.
    func clearQuery() {
        session?.clearQuery()
    }

    func applyQualifierSuggestion() {
        session?.applyQualifierSuggestion()
    }

    func invokeFilter(id: String) {
        session?.invokeFilter(id: id)
    }

    func clearFilters() {
        session?.clearFilters()
    }

    func saveFilters() {
        session?.saveFilters()
    }

    func toggleFilterSection(sectionId: String) {
        session?.toggleFilterSection(sectionId: sectionId)
    }

    func invokeSort(id: String) {
        session?.invokeSort(id: id)
    }

    func clearSort() {
        session?.clearSort()
    }

    func invokeToolbarAction(id: String) {
        session?.invokeToolbarAction(id: id)
    }

    func invokeSelectionAction(id: String) {
        session?.invokeSelectionAction(id: id)
    }

    func invokeSelectionAction(id: String, selectedIds: Set<String>) {
        guard selection.selectedIds == selectedIds else { return }
        session?.invokeSelectionActionForItems(id: id, itemIds: selectedIds.sorted())
    }

    func toggleSelection(rowId: String) {
        session?.toggleSelection(rowId: rowId)
    }

    func clearSelection() {
        session?.clearSelection()
    }

    func setOpenedRow(rowId: String) {
        session?.setOpenedRow(rowId: rowId)
    }

    /// Opens the row through the canonical Kotlin cipher-open path (routes a
    /// `VaultViewRoute` intent into the shared navigation stack).
    func openVaultRow(rowId: String) {
        session?.openVaultRow(rowId: rowId)
    }

    func performVaultRowAction(rowId: String, actionId: String) {
        session?.performVaultRowAction(rowId: rowId, actionId: actionId)
    }

    func performVaultBadgeTap(rowId: String, badgeId: String) {
        session?.performVaultBadgeTap(rowId: rowId, badgeId: badgeId)
    }

    /// Invokes one of the header's `createActions` by id.
    func createItem(actionId: String) {
        session?.createItem(actionId: actionId)
    }

    /// Reports the visible scroll anchor. The structure revision is read from
    /// the store — by contract it must identify the frame the client is
    /// RENDERING, which is exactly what the store last applied.
    func reportScroll(anchorId: String, offset: Int) {
        session?.reportScroll(
            anchorId: anchorId,
            offset: Int32(offset),
            structureRevision: store.structure.revision
        )
    }

    /// An unknown row — or a stopped session — yields `[]`. The Kotlin callback
    /// arrives on Main and fires exactly once.
    func rowActions(rowId: String) async -> [VaultAction] {
        guard let session else { return [] }
        return await withCheckedContinuation { continuation in
            session.rowActions(rowId: rowId) { bridged in
                continuation.resume(returning: bridged.map(VaultAction.init(bridged:)))
            }
        }
    }
}

// MARK: - VaultRowListModel conformance

extension VaultListSessionModel: VaultRowListModel {
    var isQueryActive: Bool { !header.query.isEmpty }

    /// The saved-filter ("custom") section's chips; the magic section id stays a
    /// main-list detail, off the row host.
    var quickFilterChips: [VaultFilterChip] {
        filterCatalog.groups.first(where: { $0.sectionId == "custom" })?.items ?? []
    }
}

extension VaultListSessionConfig {
    static func vaultMain(
        persistenceScope: String = "vaultlist",
        appBarTitle: String = "",
        appBarSubtitle: String = "",
        trash: Int32 = 0,
        archive: Int32 = 0,
        sortOverrideId: String = "",
        main: Bool = true,
        searchByPassword: Bool = false,
        canAddSecrets: Bool = true
    ) -> VaultListSessionConfig {
        VaultListSessionConfig(
            persistenceScope: persistenceScope,
            appBarTitle: appBarTitle,
            appBarSubtitle: appBarSubtitle,
            trash: trash,
            archive: archive,
            sortOverrideId: sortOverrideId,
            main: main,
            searchByPassword: searchByPassword,
            canAddSecrets: canAddSecrets,
            cipherFilterId: ""
        )
    }

    /// A custom cipher-filter navigation tab's list. The Kotlin session resolves
    /// the filter per-unlock and builds the canonical filter-tab args (title,
    /// filter, no add), so the arg fields here are inert.
    static func cipherFilter(id: String) -> VaultListSessionConfig {
        VaultListSessionConfig(
            persistenceScope: "vaultlist.cipher_filter.\(id)",
            appBarTitle: "",
            appBarSubtitle: "",
            trash: 0,
            archive: 0,
            sortOverrideId: "",
            main: false,
            searchByPassword: false,
            canAddSecrets: false,
            cipherFilterId: id
        )
    }
}
