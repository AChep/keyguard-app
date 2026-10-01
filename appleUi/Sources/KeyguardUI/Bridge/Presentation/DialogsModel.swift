import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class DialogsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Dialogs are global: any screen's action can surface one, so each is observed
    /// once for the app's lifetime and presented as an app-level sheet. Closing a dialog
    /// tears its headless producer down.
    func start() {
        startObservation(\.passwordMemorySubscription, into: \.passwordMemory, observe: core.observePasswordMemory)
        startObservation(\.largeTypeSubscription, into: \.largeType, observe: core.observeLargeType)
        startObservation(\.barcodeSubscription, into: \.barcode, observe: core.observeBarcode)
        startObservation(
            \.passkeyCredentialSubscription, into: \.passkeyCredential, observe: core.observePasskeyCredential)
        startObservation(
            \.attachmentPreviewSubscription, into: \.attachmentPreview, observe: core.observeAttachmentPreview)
        startObservation(\.confirmationSubscription, into: \.confirmation, observe: core.observeConfirmation)
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
        startObservation(
            \.cipherLinkPickerSubscription, into: \.cipherLinkPicker, observe: core.observeCipherLinkPicker)
        // The create-form ownership "Save to" account picker.
        startObservation(\.accountPickerSubscription, into: \.accountPicker, observe: core.observeAccountPicker)
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
        core.setConfirmationItemBoolean(key: key, value: value)
    }

    func setConfirmationItemString(key: String, text: String) {
        core.setConfirmationItemString(key: key, text: text)
    }

    /// Selects an option of a confirmation CHOICE item identified by `key`.
    func selectConfirmationItemEnum(key: String, optionKey: String) {
        core.selectConfirmationItemEnum(key: key, optionKey: optionKey)
    }

    /// Adds a row to a list confirmation (Change tags).
    func addConfirmationItem() { core.addConfirmationItem() }

    func removeConfirmationItem(key: String) { core.removeConfirmationItem(key: key) }

    /// Opens the native file picker for a confirmation FILE item identified by `key`.
    func selectConfirmationItemFile(key: String) {
        core.selectConfirmationItemFile(key: key)
    }

    /// Opens the "Learn more" link of the selected option of confirmation CHOICE item `key`.
    func openConfirmationItemDoc(key: String) {
        core.openConfirmationItemDoc(key: key)
    }

    func clearConfirmationItemFile(key: String) {
        core.clearConfirmationItemFile(key: key)
    }

    /// Only enabled while every item validates; the shared producer runs the action,
    /// then the dialog dismisses.
    func confirmConfirmation() {
        core.confirmConfirmation()
    }

    func closeConfirmation() {
        core.closeConfirmation()
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
        core.setCipherLinkPickerQuery(text: text)
    }

    func selectCipherLinkPickerItem(id: String) {
        core.selectCipherLinkPickerItem(id: id)
    }

    func closeCipherLinkPicker() {
        core.closeCipherLinkPicker()
    }

    func setAccountPickerNewFolderName(_ text: String) {
        core.setAccountPickerNewFolderName(text: text)
    }

    func selectAccountPickerItem(key: String) {
        core.selectAccountPickerItem(key: key)
    }

    /// The shared producer sends the chosen ownership back into the create form,
    /// then the dialog dismisses.
    func confirmAccountPicker() {
        core.confirmAccountPicker()
    }

    func closeAccountPicker() {
        core.closeAccountPicker()
    }
}
