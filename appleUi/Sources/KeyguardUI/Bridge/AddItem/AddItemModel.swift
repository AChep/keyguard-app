import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AddItemModel {
    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let closeLinkPicker: () -> Void
    private let clearEditForm: (String) -> Void

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            closeLinkPicker: { core.closeCipherLinkPicker() },
            clearEditForm: { core.clearEditForm(requestId: $0) }
        )
    }

    init(
        coreProvider: @escaping () -> KeyguardCore,
        closeLinkPicker: @escaping () -> Void,
        clearEditForm: @escaping (String) -> Void
    ) {
        self.coreProvider = coreProvider
        self.closeLinkPicker = closeLinkPicker
        self.clearEditForm = clearEditForm
    }

    private(set) var addForm: AddItemFormSnapshot = AddItemFormSnapshot.companion.empty

    private(set) var addFormDidSave = false
    private(set) var keyGenerator: AddKeyGeneratorModel?

    func startKeyGenerator(item: AddItemSnapshot) {
        guard isAddFormActive else { return }
        stopKeyGenerator()
        let generator = AddKeyGeneratorModel(core: core, itemId: item.id, isGpg: item.kind == .gpgKey)
        keyGenerator = generator
        generator.start()
    }

    func stopKeyGenerator() {
        keyGenerator?.stop()
        keyGenerator = nil
    }

    func useGeneratedKey() {
        if keyGenerator?.useKey() == true { stopKeyGenerator() }
    }

    @ObservationIgnored private var addFormSubscription: BridgeObservation?

    @ObservationIgnored private var addFormObservationID: UUID?

    @ObservationIgnored private var activeEditFormRequestId: String?

    /// Routes ownership sheets above an active add form.
    private(set) var isAddFormActive = false

    func setAddFieldText(id: String, text: String) {
        core.setAddFieldText(id: id, text: text)
    }

    /// Starts a fresh create-cipher form for the given `DSecret.Type` name
    /// (`Login` / `SecureNote` / `Card` / `Identity` / `SshKey`). Call when the
    /// create sheet appears; balance with `stopAddFormObservation()` on dismiss.
    func startAddCipherObservation(
        type: String,
        name: String? = nil,
        username: String? = nil,
        password: String? = nil
    ) {
        startAddFormObservation { onChange, onClose in
            BridgeObservation(
                core.observeAddCipher(
                    type: type,
                    name: name,
                    username: username,
                    password: password,
                    onClose: onClose,
                    onChange: onChange
                ))
        }
    }

    /// Starts a fresh create-Send form for the given `DSend.Type` name
    /// (`Text` / `File`). Call when the create sheet appears; balance with
    /// `stopAddFormObservation()` on dismiss.
    func startAddSendObservation(type: String) {
        startAddFormObservation { onChange, onClose in
            BridgeObservation(core.observeAddSend(type: type, onClose: onClose, onChange: onChange))
        }
    }

    func startEditCipherObservation(requestId: String) {
        startAddFormObservation(requestId: requestId) { onChange, onClose in
            BridgeObservation(core.observeEditCipher(requestId: requestId, onClose: onClose, onChange: onChange))
        }
    }

    /// Starts the edit-Send form for a stashed request id (the Send detail "edit"
    /// action). The shared `sendAddStateProducer` runs pre-filled from the stashed
    /// `initialValue`; balance with `stopAddFormObservation()` on dismiss.
    func startEditSendObservation(requestId: String) {
        startAddFormObservation(requestId: requestId) { onChange, onClose in
            BridgeObservation(core.observeEditSend(requestId: requestId, onClose: onClose, onChange: onChange))
        }
    }

    func startAddFormObservation(
        requestId: String? = nil,
        observe: (
            _ onChange: @escaping (AddItemFormSnapshot) -> Void,
            _ onClose: @escaping () -> Void
        ) -> BridgeObservation
    ) {
        stopAddFormObservation()
        activeEditFormRequestId = requestId
        let observationID = UUID()
        addFormObservationID = observationID
        isAddFormActive = true
        addFormSubscription = observe(
            { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    guard let self, self.addFormObservationID == observationID else { return }
                    if !snapshot.loaded { self.stopKeyGenerator() }
                    self.addForm = snapshot
                }
            },
            { [weak self] in
                Task { @MainActor [weak self] in
                    guard let self, self.addFormObservationID == observationID else { return }
                    self.addFormDidSave = true
                }
            }
        )
    }

    /// Called by the presenting sheet on actual dismissal, never on a temporary
    /// disappearance beneath a confirmation or file picker.
    func stopAddFormObservation() {
        stopKeyGenerator()
        addFormObservationID = nil
        isAddFormActive = false
        closeLinkPicker()
        addFormSubscription?.cancel()
        addFormSubscription = nil
        if let requestId = activeEditFormRequestId {
            clearEditForm(requestId)
            activeEditFormRequestId = nil
        }
        addForm = AddItemFormSnapshot.companion.empty
        addFormDidSave = false
    }

    /// Writes text into a create-form field identified by its `AddTextFieldSnapshot.id`.
    func setAddField(id: String, text: String) {
        core.setAddField(id: id, text: text)
    }

    /// Toggles a create-form switch identified by its `AddItemSnapshot.switchId`.
    func setAddSwitch(id: String, value: Bool) {
        core.setAddSwitch(id: id, value: value)
    }

    /// Invokes a create-form action / option closure by its opaque snapshot id.
    func invokeAddAction(id: String) {
        core.invokeAddAction(id: id)
    }

    func scanAddTotp(id: String, value: String) {
        core.scanAddTotp(id: id, value: value)
    }

    /// Adds a file dropped onto the form as an attachment.
    func dropFileOnForm(url: URL) {
        let file = url.fileNameAndSize
        core.dropFileOnAddForm(uri: url.absoluteString, name: file.name, size: file.size)
    }

    /// Feeds a file dropped onto the row `itemId`, e.g. replacing a Send's file.
    func dropFile(onItem itemId: String, url: URL) {
        let file = url.fileNameAndSize
        core.dropFileOnAddItem(itemId: itemId, uri: url.absoluteString, name: file.name, size: file.size)
    }

    /// Submits the active create form (runs the shared `AddCipher` / `AddSend`).
    func submitAddItem() {
        core.submitAddItem()
    }

    /// Opens the create-form ownership "Save to" account picker (fires the producer's
    /// ownership onClick, which surfaces the `accountPicker` dialog).
    func invokeAddOwnership() {
        core.invokeAddOwnership()
    }
}
