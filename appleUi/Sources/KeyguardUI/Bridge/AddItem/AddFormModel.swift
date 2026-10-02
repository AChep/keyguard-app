import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AddFormModel: SnapshotObserving {
    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private var session: AddFormSession?
    private(set) var dialogs: DialogsModel?
    private(set) var autofillGenerator: AutofillGeneratorModel?
    let filePicker = FilePickerSession()
    private(set) var pendingDatePicker: PendingDatePicker?

    convenience init(core: KeyguardCore) {
        self.init(coreProvider: { core })
    }

    init(
        coreProvider: @escaping () -> KeyguardCore
    ) {
        self.coreProvider = coreProvider
    }

    private(set) var addForm: AddItemFormSnapshot = AddItemFormSnapshot.companion.empty

    private(set) var addFormDidSave = false
    private(set) var keyGenerator: AddKeyGeneratorModel?

    func startKeyGenerator(item: AddItemSnapshot) {
        guard isAddFormActive, let session else { return }
        stopKeyGenerator()
        let generator = AddKeyGeneratorModel(session: session, itemId: item.id, isGpg: item.kind == .gpgKey)
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

    /// True while this presentation owns a producer.
    private(set) var isAddFormActive = false

    func setAddFieldText(id: String, text: String) {
        session?.setAddFieldText(id: id, text: text)
    }

    /// `type` is a `DSecret.Type` name (`Login` / `SecureNote` / `Card` / `Identity` /
    /// `SshKey`). Call when the create sheet appears; balance with `stopAddFormObservation()`.
    func startAddCipherObservation(
        type: String,
        name: String? = nil,
        username: String? = nil,
        password: String? = nil
    ) {
        start(session: core.makeAddCipherSession(type: type, name: name, username: username, password: password))
    }

    func startAddSendObservation(type: String) {
        start(session: core.makeAddSendSession(type: type))
    }

    func startEditCipherObservation(requestId: String) {
        start(session: core.makeEditCipherSession(requestId: requestId))
    }

    /// The shared producer runs pre-filled from the request's stashed `initialValue`;
    /// balance with `stopAddFormObservation()`.
    func startEditSendObservation(requestId: String) {
        start(session: core.makeEditSendSession(requestId: requestId))
    }

    private func start(session: AddFormSession) {
        startAddFormObservation { onChange, onClose in
            self.session = session
            let dialogs = DialogsModel(core: core, formDialogs: session.dialogs)
            self.dialogs = dialogs
            dialogs.startFormDialogs()
            autofillGenerator = AutofillGeneratorModel(session: session)
            // Requests only reach the presentation that still owns this session.
            session.setFilePickerRequestHandler { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.session === session else { return }
                    self.filePicker.presentFilePicker(
                        for: request,
                        resolve: { [weak self] id, uri, name, size, token in
                            guard self?.session === session else { return }
                            session.resolveFilePicker(
                                requestId: id, uri: uri, name: name, size: size, accessToken: token)
                        },
                        cancel: { [weak self] id in
                            guard self?.session === session else { return }
                            session.cancelFilePicker(requestId: id)
                        }
                    )
                }
            }
            session.setDatePickerRequestHandler { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.session === session else { return }
                    self.cancelDatePicker()
                    self.pendingDatePicker = PendingDatePicker(request)
                }
            }
            return BridgeObservation(session.observe(onChange: onChange, onClose: onClose))
        }
    }

    func resolveDatePicker(requestId: String, year: Int32, month: Int32, day: Int32, hour: Int32, minute: Int32) {
        guard pendingDatePicker?.request.requestId == requestId else { return }
        pendingDatePicker = nil
        session?.resolveDatePicker(requestId: requestId, year: year, month: month, day: day, hour: hour, minute: minute)
    }

    func cancelDatePicker() {
        guard let pending = pendingDatePicker else { return }
        pendingDatePicker = nil
        session?.cancelDatePicker(requestId: pending.request.requestId)
    }

    func startAddFormObservation(
        observe: (
            _ onChange: @escaping (AddItemFormSnapshot) -> Void,
            _ onClose: @escaping () -> Void
        ) -> BridgeObservation
    ) {
        stopAddFormObservation()
        isAddFormActive = true
        startObservation(\.addFormSubscription) { deliver in
            observe(
                { snapshot in
                    deliver { model in
                        if !snapshot.loaded { model.stopKeyGenerator() }
                        model.addForm = snapshot
                    }
                },
                {
                    deliver { model in
                        model.stopChildren()
                        model.addFormDidSave = true
                    }
                }
            )
        }
    }

    /// Called by the presenting sheet on actual dismissal, never on a temporary
    /// disappearance beneath a confirmation or file picker.
    func stopAddFormObservation() {
        stopChildren()
        isAddFormActive = false
        stopObservation(\.addFormSubscription)
        session?.close()
        session = nil
        addForm = AddItemFormSnapshot.companion.empty
        addFormDidSave = false
    }

    private func stopChildren() {
        stopKeyGenerator()
        filePicker.cancel()
        cancelDatePicker()
        autofillGenerator?.stopAutofillGeneratorObservation()
        dialogs?.stopFormDialogs()
    }

    /// Writes text into a create-form field identified by its `AddTextFieldSnapshot.id`.
    func setAddField(id: String, text: String) {
        session?.setAddField(id: id, text: text)
    }

    /// Toggles a create-form switch identified by its `AddItemSnapshot.switchId`.
    func setAddSwitch(id: String, value: Bool) {
        session?.setAddSwitch(id: id, value: value)
    }

    func invokeAddAction(id: String) {
        session?.invokeAddAction(id: id)
    }

    func scanAddTotp(id: String, value: String) {
        session?.scanAddTotp(id: id, value: value)
    }

    /// Adds a file dropped onto the form as an attachment.
    func dropFileOnForm(url: URL) {
        let file = url.fileNameAndSize
        session?.dropFileOnAddForm(uri: url.absoluteString, name: file.name, size: file.size)
    }

    /// Feeds a file dropped onto the row `itemId`, e.g. replacing a Send's file.
    func dropFile(onItem itemId: String, url: URL) {
        let file = url.fileNameAndSize
        session?.dropFileOnAddItem(itemId: itemId, uri: url.absoluteString, name: file.name, size: file.size)
    }

    func submitAddItem() {
        session?.submitAddItem()
    }

    /// Opens the create-form ownership "Save to" account picker (the `accountPicker` dialog).
    func invokeAddOwnership() {
        session?.invokeAddOwnership()
    }
}
