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

    /// Only live while a Send is selected in the two-pane layout.
    private var sendDetailState = ObservedDetail<SendDetailSnapshot, SendDetailIdentity>(
        snapshot: SendDetailSnapshot.companion.empty)

    var sendDetail: SendDetailSnapshot { sendDetailState.snapshot }
    var sendDetailIdentity: SendDetailIdentity? { sendDetailState.identity }

    @ObservationIgnored private var sendListSubscription: BridgeObservation?

    @ObservationIgnored private var sendDetailSubscription: BridgeObservation?

    /// Call when the Send screen appears; balance with `stopSendListObservation()`.
    func startSendListObservation() {
        startObservation(\.sendListSubscription) { deliver in
            BridgeObservation(
                core.observeSendList { snapshot in
                    deliver { $0.setSendListSnapshot(snapshot) }
                })
        }
    }

    func stopSendListObservation() {
        stopObservation(\.sendListSubscription)
        setSendListSnapshot(SendListSnapshot.companion.empty)
    }

    private func setSendListSnapshot(_ snapshot: SendListSnapshot) {
        sendList = snapshot
        #if os(macOS)
        sendFilterToolbar = FilterToolbarState(snapshot: snapshot)
        sendSortToolbar = SendSortToolbarState(snapshot: snapshot)
        sendCreateToolbar = SendCreateToolbarState(snapshot: snapshot)
        sendActionsToolbar = ListActionsToolbarState(actions: snapshot.listActions)
        #endif
    }

    func setSendListQuery(_ text: String) {
        core.setSendListQuery(text: text)
    }

    /// Toggles a Send filter on/off (or expands/collapses a section) by its id.
    func invokeSendListFilter(id: String) {
        core.invokeSendListFilter(id: id)
    }

    func invokeSendListSort(id: String) {
        core.invokeSendListSort(id: id)
    }

    func clearSendListFilters() {
        core.clearSendListFilters()
    }

    func clearSendListSort() {
        core.clearSendListSort()
    }

    func toggleSendListSelection(id: String) {
        core.toggleSendListSelection(itemId: id)
    }

    func invokeSendListSelectionAction(id: String) {
        core.invokeSendListSelectionAction(id: id)
    }

    /// Runs a top-level Send-list overflow action.
    func invokeSendListAction(id: String) {
        core.invokeSendListAction(id: id)
    }

    func clearSendListSelection() {
        core.clearSendListSelection()
    }

    #if os(macOS)
    /// Fires the shared producer's `onFileDrop`, which opens the native create sheet
    /// pre-filled with the dropped file.
    func dropFileOnSendList(url: URL) {
        let file = url.fileNameAndSize
        core.dropFileOnSendList(uri: url.absoluteString, name: file.name, size: file.size)
    }
    #endif

    /// Call when a Send is selected; balance with `stopSendDetailObservation()`.
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

    /// Invokes a Send detail item / header context action.
    func invokeSendAction(id: String) {
        core.invokeSendAction(id: id)
    }

    /// Copies the share link of the currently observed Send.
    func sendCopy() {
        core.sendCopy()
    }

    func sendShare() {
        core.sendShare()
    }

    func sendEdit() {
        core.sendEdit()
    }
}

struct SendDetailIdentity: Hashable {
    let itemId: String
    let accountId: String
}
