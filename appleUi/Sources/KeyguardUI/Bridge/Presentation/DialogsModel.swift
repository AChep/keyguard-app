import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class DialogsModel: SnapshotObserving {
    private let core: KeyguardCore
    private let formDialogs: any FormDialogsSource

    init(core: KeyguardCore, formDialogs: (any FormDialogsSource)? = nil) {
        self.core = core
        self.formDialogs = formDialogs ?? core
    }

    /// Starts all app-level dialog channels. Editors use `startFormDialogs()`
    /// with their own source and present those dialogs above the owning form.
    func start() {
        startObservation(\.passwordMemorySubscription, into: \.passwordMemory, observe: core.observePasswordMemory)
        startObservation(\.largeTypeSubscription, into: \.largeType, observe: core.observeLargeType)
        startObservation(\.barcodeSubscription, into: \.barcode, observe: core.observeBarcode)
        startObservation(
            \.passkeyCredentialSubscription, into: \.passkeyCredential, observe: core.observePasskeyCredential)
        startObservation(
            \.attachmentPreviewSubscription, into: \.attachmentPreview, observe: core.observeAttachmentPreview)
        startObservation(\.elevatedAccessSubscription, into: \.elevatedAccess, observe: core.observeElevatedAccess)
        // The cipher detail's inactive-TOTP / inactive-passkey info dialog.
        startObservation(\.serviceInfoSubscription, into: \.serviceInfo, observe: core.observeServiceInfo)
        // The HIBP breach dialogs (email / username, password, website).
        startObservation(\.emailLeakSubscription, into: \.emailLeak, observe: core.observeEmailLeak)
        startObservation(\.passwordLeakSubscription, into: \.passwordLeak, observe: core.observePasswordLeak)
        startObservation(\.websiteLeakSubscription, into: \.websiteLeak, observe: core.observeWebsiteLeak)
        // The account "Change color" picker.
        startObservation(\.colorPickerSubscription, into: \.colorPicker, observe: core.observeColorPicker)
        // The collection / organization read-only "info" dialog.
        startObservation(\.infoDialogSubscription, into: \.infoDialog, observe: core.observeInfoDialog)
        startFormDialogs()
    }

    func startFormDialogs() {
        startObservation(\.confirmationSubscription, into: \.confirmation, observe: formDialogs.observeConfirmation)
        startObservation(
            \.cipherLinkPickerSubscription, into: \.cipherLinkPicker, observe: formDialogs.observeCipherLinkPicker)
        startObservation(\.accountPickerSubscription, into: \.accountPicker, observe: formDialogs.observeAccountPicker)
    }

    func stopFormDialogs() {
        stopObservation(\.confirmationSubscription, resetting: \.confirmation, to: nil)
        stopObservation(\.cipherLinkPickerSubscription, resetting: \.cipherLinkPicker, to: nil)
        stopObservation(\.accountPickerSubscription, resetting: \.accountPicker, to: nil)
    }

    private(set) var passwordMemory: PasswordMemorySnapshot?

    private(set) var largeType: LargeTypeSnapshot?

    private(set) var barcode: BarcodeSnapshot?

    private(set) var passkeyCredential: PasskeyCredentialSnapshot?

    private(set) var attachmentPreview: AttachmentPreviewSnapshot?

    private(set) var confirmation: ConfirmationSnapshot?

    /// The master-password re-prompt.
    private(set) var elevatedAccess: ElevatedAccessSnapshot?

    // Auxiliary windows and Recents present authentication in their own sheet
    // hierarchy. Only one host may present the shared request at a time.
    var elevatedAccessLocalHosts: Set<ElevatedAccessLocalHost> = []

    var elevatedAccessLocalHost: ElevatedAccessLocalHost? {
        elevatedAccessLocalHosts.max(by: { $0.rawValue < $1.rawValue })
    }

    private(set) var serviceInfo: ServiceDirectoryDetailSnapshot?

    private(set) var emailLeak: EmailLeakSnapshot?

    private(set) var passwordLeak: PasswordLeakSnapshot?

    private(set) var websiteLeak: WebsiteLeakSnapshot?

    private(set) var colorPicker: ColorPickerSnapshot?

    private(set) var infoDialog: InfoDialogSnapshot?

    private(set) var accountPicker: AccountPickerSnapshot?

    private(set) var cipherLinkPicker: CipherLinkPickerSnapshot?

    @ObservationIgnored private var passwordMemorySubscription: BridgeObservation?

    @ObservationIgnored private var largeTypeSubscription: BridgeObservation?

    @ObservationIgnored private var barcodeSubscription: BridgeObservation?

    @ObservationIgnored private var passkeyCredentialSubscription: BridgeObservation?

    @ObservationIgnored private var attachmentPreviewSubscription: BridgeObservation?

    @ObservationIgnored private var confirmationSubscription: BridgeObservation?

    @ObservationIgnored private var elevatedAccessSubscription: BridgeObservation?

    @ObservationIgnored private var serviceInfoSubscription: BridgeObservation?

    @ObservationIgnored private var emailLeakSubscription: BridgeObservation?

    @ObservationIgnored private var passwordLeakSubscription: BridgeObservation?

    @ObservationIgnored private var websiteLeakSubscription: BridgeObservation?

    @ObservationIgnored private var colorPickerSubscription: BridgeObservation?

    @ObservationIgnored private var infoDialogSubscription: BridgeObservation?

    @ObservationIgnored private var accountPickerSubscription: BridgeObservation?

    @ObservationIgnored private var cipherLinkPickerSubscription: BridgeObservation?

    /// Highlights every Large Type tile up to (and including) `index`.
    func selectLargeTypeSymbol(index: Int) {
        core.selectLargeTypeSymbol(index: Int32(index))
    }

    func setPasswordMemoryText(_ text: String) { core.setPasswordMemoryText(text: text) }

    func verifyPasswordMemory() { core.verifyPasswordMemory() }

    func closePasswordMemory() { core.closePasswordMemory() }

    func closeLargeType() {
        core.closeLargeType()
    }

    func selectBarcodeFormat(id: String) {
        core.selectBarcodeFormat(id: id)
    }

    func closeBarcode() {
        core.closeBarcode()
    }

    /// Only available when the producer exposes the use action (pick-passkey app mode).
    func usePasskeyCredential() {
        core.usePasskeyCredential()
    }

    func closePasskeyCredential() {
        core.closePasskeyCredential()
    }

    /// Copies the previewed attachment's text content through the shared
    /// CopyText, so clipboard auto-clear and copy events keep working.
    func copyAttachmentPreviewText() {
        core.invokeAttachmentPreviewCopy()
    }

    func closeAttachmentPreview() {
        core.closeAttachmentPreview()
    }

    func setConfirmationItemBoolean(key: String, value: Bool) {
        formDialogs.setConfirmationItemBoolean(key: key, value: value)
    }

    func setConfirmationItemString(key: String, text: String) {
        formDialogs.setConfirmationItemString(key: key, text: text)
    }

    /// Selects an option of a confirmation CHOICE item identified by `key`.
    func selectConfirmationItemEnum(key: String, optionKey: String) {
        formDialogs.selectConfirmationItemEnum(key: key, optionKey: optionKey)
    }

    /// Adds a row to a list confirmation (Change tags).
    func addConfirmationItem() { formDialogs.addConfirmationItem() }

    func removeConfirmationItem(key: String) { formDialogs.removeConfirmationItem(key: key) }

    /// Opens the native file picker for a confirmation FILE item identified by `key`.
    func selectConfirmationItemFile(key: String) {
        formDialogs.selectConfirmationItemFile(key: key)
    }

    /// Opens the "Learn more" link of the selected option of confirmation CHOICE item `key`.
    func openConfirmationItemDoc(key: String) {
        formDialogs.openConfirmationItemDoc(key: key)
    }

    func clearConfirmationItemFile(key: String) {
        formDialogs.clearConfirmationItemFile(key: key)
    }

    /// Only enabled while every item validates; the shared producer runs the action,
    /// then the dialog dismisses.
    func confirmConfirmation() {
        formDialogs.confirmConfirmation()
    }

    func closeConfirmation() {
        formDialogs.closeConfirmation()
    }

    func setElevatedAccessPassword(text: String) {
        core.setElevatedAccessPassword(text: text)
    }

    func triggerElevatedAccessBiometric() {
        core.triggerElevatedAccessBiometric()
    }

    func triggerElevatedAccessYubiKey() {
        core.triggerElevatedAccessYubiKey()
    }

    /// Confirms the re-prompt with the typed master password; on success the shared
    /// producer grants access and the original copy / reveal / edit action runs.
    func confirmElevatedAccess() {
        core.confirmElevatedAccess()
    }

    func closeElevatedAccess() {
        core.closeElevatedAccess()
    }

    func closeServiceInfo() {
        core.closeServiceInfo()
    }

    func closeEmailLeak() {
        core.closeEmailLeak()
    }

    func closePasswordLeak() {
        core.closePasswordLeak()
    }

    func closeWebsiteLeak() {
        core.closeWebsiteLeak()
    }

    func selectColorPickerSwatch(id: String) {
        core.selectColorPickerSwatch(id: id)
    }

    /// The shared producer persists the chosen account color, then the dialog dismisses.
    func confirmColorPicker() {
        core.confirmColorPicker()
    }

    func closeColorPicker() {
        core.closeColorPicker()
    }

    func closeInfoDialog() {
        core.closeInfoDialog()
    }

    func setCipherLinkPickerQuery(_ text: String) {
        formDialogs.setCipherLinkPickerQuery(text: text)
    }

    func selectCipherLinkPickerItem(id: String) {
        formDialogs.selectCipherLinkPickerItem(id: id)
    }

    func closeCipherLinkPicker() {
        formDialogs.closeCipherLinkPicker()
    }

    func setAccountPickerNewFolderName(_ text: String) {
        formDialogs.setAccountPickerNewFolderName(text: text)
    }

    func selectAccountPickerItem(key: String) {
        formDialogs.selectAccountPickerItem(key: key)
    }

    /// The shared producer sends the chosen ownership back into the create form,
    /// then the dialog dismisses.
    func confirmAccountPicker() {
        formDialogs.confirmAccountPicker()
    }

    func closeAccountPicker() {
        formDialogs.closeAccountPicker()
    }
}
