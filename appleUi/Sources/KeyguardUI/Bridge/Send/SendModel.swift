import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class SendModel: SnapshotObserving {
    typealias DetailObserver = (String, String, @escaping (SendDetailSnapshot) -> Void) -> BridgeObservation

    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observeDetail: DetailObserver

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            observeDetail: { BridgeObservation(core.observeSendDetail(itemId: $0, accountId: $1, onChange: $2)) }
        )
    }

    init(
        coreProvider: @escaping () -> KeyguardCore,
        observeDetail: @escaping DetailObserver
    ) {
        self.coreProvider = coreProvider
        self.observeDetail = observeDetail
    }

    /// Searchable / filterable / sortable Send list state, produced by the shared
    /// Kotlin `sendListScreenStateProducer` running headless inside `KeyguardCore`.
    /// Only live while the Send screen is on screen.
    private(set) var sendList: SendListSnapshot = SendListSnapshot.companion.empty

    #if os(macOS)
    private(set) var sendFilterToolbar = FilterToolbarState.empty
    #endif

    #if os(macOS)
    private(set) var sendSortToolbar = SendSortToolbarState.empty
    #endif

    #if os(macOS)
    private(set) var sendCreateToolbar = SendCreateToolbarState.empty
    #endif

    #if os(macOS)
    private(set) var sendActionsToolbar = ListActionsToolbarState.empty
    #endif

    /// Full detail of the selected Send, produced by the shared Kotlin
    /// `sendViewScreenStateProducer` running headless inside `KeyguardCore`. Only
    /// live while a Send is selected in the two-pane layout.
    private var sendDetailState = ObservedDetail<SendDetailSnapshot, SendDetailIdentity>(
        snapshot: SendDetailSnapshot.companion.empty)

    var sendDetail: SendDetailSnapshot { sendDetailState.snapshot }
    var sendDetailIdentity: SendDetailIdentity? { sendDetailState.identity }

    @ObservationIgnored private var sendListSubscription: BridgeObservation?

    @ObservationIgnored private var sendDetailSubscription: BridgeObservation?

    /// Starts running the shared Send list producer. Call when the Send screen
    /// appears; balance with `stopSendListObservation()` on disappear.
    func startSendListObservation() {
        guard sendListSubscription == nil else { return }
        sendListSubscription = BridgeObservation(
            core.observeSendList { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.setSendListSnapshot(snapshot)
                }
            })
    }

    func stopSendListObservation() {
        sendListSubscription?.cancel()
        sendListSubscription = nil
        setSendListSnapshot(SendListSnapshot.companion.empty)
    }

    private func setSendListSnapshot(_ snapshot: SendListSnapshot) {
        sendList = snapshot
        #if os(macOS)
        assignIfChanged(&sendFilterToolbar, FilterToolbarState(snapshot: snapshot))
        assignIfChanged(&sendSortToolbar, SendSortToolbarState(snapshot: snapshot))
        assignIfChanged(&sendCreateToolbar, SendCreateToolbarState(snapshot: snapshot))
        assignIfChanged(&sendActionsToolbar, ListActionsToolbarState(actions: snapshot.listActions))
        #endif
    }

    /// Forwards typed text into the shared Send list search field.
    func setSendListQuery(_ text: String) {
        core.setSendListQuery(text: text)
    }

    /// Toggles a Send filter on/off (or expands/collapses a section) by its id.
    func invokeSendListFilter(id: String) {
        core.invokeSendListFilter(id: id)
    }

    /// Selects a Send sort option by its snapshot id.
    func invokeSendListSort(id: String) {
        core.invokeSendListSort(id: id)
    }

    /// Clears every active Send list filter.
    func clearSendListFilters() {
        core.clearSendListFilters()
    }

    /// Resets the Send list sort back to its default.
    func clearSendListSort() {
        core.clearSendListSort()
    }

    /// Toggles whether the Send with the given snapshot id is part of the
    /// multi-selection. Routes through the shared producer's selection handle.
    func toggleSendListSelection(id: String) {
        core.toggleSendListSelection(itemId: id)
    }

    /// Runs a bulk action of the active Send multi-selection by its snapshot id.
    func invokeSendListSelectionAction(id: String) {
        core.invokeSendListSelectionAction(id: id)
    }

    /// Runs a top-level Send-list overflow action (the keyboard toggle / sync / lock)
    /// by its snapshot id.
    func invokeSendListAction(id: String) {
        core.invokeSendListAction(id: id)
    }

    /// Clears the active Send multi-selection.
    func clearSendListSelection() {
        core.clearSendListSelection()
    }

    #if os(macOS)
    /// Creates a new File send from a file dropped onto the Send list (macOS). Fires
    /// the shared producer's `onFileDrop`, which opens the native create sheet
    /// pre-filled with the dropped file.
    func dropFileOnSendList(url: URL) {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .nameKey])
        let size = Int64(values?.fileSize ?? -1)
        let name = values?.name ?? url.lastPathComponent
        core.dropFileOnSendList(uri: url.absoluteString, name: name, size: size)
    }
    #endif

    /// Starts running the shared Send detail producer for the given Send. Call
    /// when a Send is selected; balance with `stopSendDetailObservation()`.
    func startSendDetailObservation(itemId: String, accountId: String) {
        stopSendDetailObservation()
        let identity = SendDetailIdentity(itemId: itemId, accountId: accountId)
        startObservation(\.sendDetailSubscription, into: \.sendDetailState) { onChange in
            observeDetail(itemId, accountId) { snapshot in
                onChange(ObservedDetail(snapshot: snapshot, identity: identity))
            }
        }
    }

    func stopSendDetailObservation() {
        stopObservation(
            \.sendDetailSubscription, resetting: \.sendDetailState,
            to: ObservedDetail(snapshot: SendDetailSnapshot.companion.empty))
    }

    /// Invokes a Send detail item / header context action by its snapshot id.
    func invokeSendAction(id: String) {
        core.invokeSendAction(id: id)
    }

    /// Copies the share link of the currently observed Send.
    func sendCopy() {
        core.sendCopy()
    }

    /// Opens the native share sheet for the currently observed Send.
    func sendShare() {
        core.sendShare()
    }

    /// Opens the currently observed Send for editing.
    func sendEdit() {
        core.sendEdit()
    }
}

struct SendDetailIdentity: Hashable {
    let itemId: String
    let accountId: String
}
