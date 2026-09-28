import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class DialogsModel {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    @ObservationIgnored private var started = false

    func start() {
        guard !started else { return }
        started = true
        // The "Show in Large Type" dialog is global: any field's action surfaces it,
        // so observe it once at the app level and present an app-level sheet.
        passwordMemorySubscription = BridgeObservation(
            core.observePasswordMemory { [weak self] snapshot in
                Task { @MainActor [weak self] in self?.passwordMemory = snapshot }
            })
        largeTypeSubscription = BridgeObservation(
            core.observeLargeType { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.largeType = snapshot
                }
            })
        // The "Show as Barcode" dialog is global in the same way; observe it once
        // and present an app-level sheet.
        barcodeSubscription = BridgeObservation(
            core.observeBarcode { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.barcode = snapshot
                }
            })
        // The passkey credential detail dialog is global in the same way; observe
        // it once and present an app-level sheet.
        passkeyCredentialSubscription = BridgeObservation(
            core.observePasskeyCredential { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.passkeyCredential = snapshot
                }
            })
        // The attachment preview dialog is global in the same way; observe it
        // once and present an app-level sheet.
        attachmentPreviewSubscription = BridgeObservation(
            core.observeAttachmentPreview { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.attachmentPreview = snapshot
                }
            })
        // The generic confirmation dialog is global in the same way; observe it
        // once and present an app-level sheet.
        confirmationSubscription = BridgeObservation(
            core.observeConfirmation { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.confirmation = snapshot
                }
            })
        // The master-password re-prompt dialog is global in the same way; observe it
        // once and present an app-level sheet.
        elevatedAccessSubscription = BridgeObservation(
            core.observeElevatedAccess { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.elevatedAccess = snapshot
                }
            })
        // The cipher detail's inactive-TOTP / inactive-passkey info dialog is global
        // in the same way; observe it once and present an app-level sheet.
        serviceInfoSubscription = BridgeObservation(
            core.observeServiceInfo { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.serviceInfo = snapshot
                }
            })
        // The HIBP breach dialogs (email / username, password, website) are global
        // in the same way; observe each once and present an app-level sheet.
        emailLeakSubscription = BridgeObservation(
            core.observeEmailLeak { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.emailLeak = snapshot
                }
            })
        passwordLeakSubscription = BridgeObservation(
            core.observePasswordLeak { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.passwordLeak = snapshot
                }
            })
        websiteLeakSubscription = BridgeObservation(
            core.observeWebsiteLeak { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.websiteLeak = snapshot
                }
            })
        // The account "Change color" picker dialog is global in the same way; observe
        // it once and present an app-level sheet.
        colorPickerSubscription = BridgeObservation(
            core.observeColorPicker { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.colorPicker = snapshot
                }
            })
        // The collection / organization read-only "info" dialog is global in the same
        // way; observe it once and present an app-level sheet.
        infoDialogSubscription = BridgeObservation(
            core.observeInfoDialog { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.infoDialog = snapshot
                }
            })
        // The create-form ownership "Save to" account picker is global in the same
        // way; observe it once and present an app-level sheet.
        cipherLinkPickerSubscription = BridgeObservation(
            core.observeCipherLinkPicker { [weak self] snapshot in
                Task { @MainActor [weak self] in self?.cipherLinkPicker = snapshot }
            })
        accountPickerSubscription = BridgeObservation(
            core.observeAccountPicker { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.accountPicker = snapshot
                }
            })
    }

    /// Current Large Type dialog state.
    private(set) var passwordMemory: PasswordMemorySnapshot?

    private(set) var largeType: LargeTypeSnapshot?

    /// Current barcode dialog state.
    private(set) var barcode: BarcodeSnapshot?

    /// Current passkey credential dialog state.
    private(set) var passkeyCredential: PasskeyCredentialSnapshot?

    /// Current attachment preview dialog state.
    private(set) var attachmentPreview: AttachmentPreviewSnapshot?

    /// Current confirmation dialog state.
    private(set) var confirmation: ConfirmationSnapshot?

    /// Current master-password re-prompt state.
    private(set) var elevatedAccess: ElevatedAccessSnapshot?

    // Auxiliary windows and Recents present authentication in their own sheet
    // hierarchy. Only one host may present the shared request at a time.
    var elevatedAccessLocalHosts: Set<ElevatedAccessLocalHost> = []

    var elevatedAccessLocalHost: ElevatedAccessLocalHost? {
        elevatedAccessLocalHosts.max(by: { $0.rawValue < $1.rawValue })
    }

    /// Current service-information dialog state.
    private(set) var serviceInfo: ServiceDirectoryDetailSnapshot?

    /// Current email/username breach dialog state.
    private(set) var emailLeak: EmailLeakSnapshot?

    /// Current password breach dialog state.
    private(set) var passwordLeak: PasswordLeakSnapshot?

    /// Current website breach dialog state.
    private(set) var websiteLeak: WebsiteLeakSnapshot?

    /// Color-picker dialog state from the shared producer.
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

    /// Highlights every Large Type tile up to (and including) `index`; the shared
    /// producer re-emits and `largeType` refreshes.
    func selectLargeTypeSymbol(index: Int) {
        core.selectLargeTypeSymbol(index: Int32(index))
    }

    /// Dismisses the Large Type dialog and tears its headless producer down.
    func setPasswordMemoryText(_ text: String) { core.setPasswordMemoryText(text: text) }

    func verifyPasswordMemory() { core.verifyPasswordMemory() }

    func closePasswordMemory() { core.closePasswordMemory() }

    func closeLargeType() {
        core.closeLargeType()
    }

    /// Switches the rendered barcode to the format with the given option id; the
    /// shared producer re-emits and `barcode` refreshes.
    func selectBarcodeFormat(id: String) {
        core.selectBarcodeFormat(id: id)
    }

    /// Dismisses the "Show as Barcode" dialog and tears its headless producer down.
    func closeBarcode() {
        core.closeBarcode()
    }

    /// Uses the shown passkey credential. Only available when the producer
    /// exposes the use action (pick-passkey app mode).
    func usePasskeyCredential() {
        core.usePasskeyCredential()
    }

    /// Dismisses the passkey credential detail dialog and tears its headless
    /// producer down.
    func closePasskeyCredential() {
        core.closePasskeyCredential()
    }

    /// Copies the previewed attachment's text content through the shared
    /// CopyText, so clipboard auto-clear and copy events keep working.
    func copyAttachmentPreviewText() {
        core.invokeAttachmentPreviewCopy()
    }

    /// Dismisses the attachment preview dialog and tears its headless producer
    /// down.
    func closeAttachmentPreview() {
        core.closeAttachmentPreview()
    }

    /// Toggles a confirmation BOOLEAN item; the shared producer re-emits and
    /// `confirmation` refreshes (including the `confirmEnabled` validation).
    func setConfirmationItemBoolean(key: String, value: Bool) {
        core.setConfirmationItemBoolean(key: key, value: value)
    }

    /// Writes text into a confirmation STRING item identified by `key`.
    func setConfirmationItemString(key: String, text: String) {
        core.setConfirmationItemString(key: key, text: text)
    }

    /// Selects an option of a confirmation ENUM item identified by `key`.
    func selectConfirmationItemEnum(key: String, optionKey: String) {
        core.selectConfirmationItemEnum(key: key, optionKey: optionKey)
    }

    /// Opens the native file picker for a confirmation FILE item identified by `key`.
    func addConfirmationItem() { core.addConfirmationItem() }

    func removeConfirmationItem(key: String) { core.removeConfirmationItem(key: key) }

    func selectConfirmationItemFile(key: String) {
        core.selectConfirmationItemFile(key: key)
    }

    /// Clears the chosen file of a confirmation FILE item identified by `key`.
    func openConfirmationItemDoc(key: String) {
        core.openConfirmationItemDoc(key: key)
    }

    func clearConfirmationItemFile(key: String) {
        core.clearConfirmationItemFile(key: key)
    }

    /// Confirms the dialog (only enabled while every item validates); the shared
    /// producer runs the action's real work, then the dialog dismisses.
    func confirmConfirmation() {
        core.confirmConfirmation()
    }

    /// Dismisses the confirmation dialog and tears its headless producer down.
    func closeConfirmation() {
        core.closeConfirmation()
    }

    /// Writes text into the re-prompt dialog's master-password field.
    func setElevatedAccessPassword(text: String) {
        core.setElevatedAccessPassword(text: text)
    }

    /// Fires the re-prompt dialog's biometric (Touch ID / Face ID) prompt.
    func triggerElevatedAccessBiometric() {
        core.triggerElevatedAccessBiometric()
    }

    /// Fires the re-prompt dialog's YubiKey challenge-response prompt.
    func triggerElevatedAccessYubiKey() {
        core.triggerElevatedAccessYubiKey()
    }

    /// Confirms the re-prompt with the typed master password; on success the shared
    /// producer grants access and the original copy / reveal / edit action runs.
    func confirmElevatedAccess() {
        core.confirmElevatedAccess()
    }

    /// Dismisses the re-prompt dialog and tears its headless producer down.
    func closeElevatedAccess() {
        core.closeElevatedAccess()
    }

    /// Dismisses the service-info dialog.
    func closeServiceInfo() {
        core.closeServiceInfo()
    }

    /// Dismisses the email / username breach dialog and tears its producer down.
    func closeEmailLeak() {
        core.closeEmailLeak()
    }

    /// Dismisses the password breach dialog and tears its producer down.
    func closePasswordLeak() {
        core.closePasswordLeak()
    }

    /// Dismisses the website breach dialog and tears its producer down.
    func closeWebsiteLeak() {
        core.closeWebsiteLeak()
    }

    /// Highlights a color-picker swatch; the shared producer re-emits and `colorPicker`
    /// refreshes (the selected index moves).
    func selectColorPickerSwatch(id: String) {
        core.selectColorPickerSwatch(id: id)
    }

    /// Confirms the color picker; the shared producer persists the chosen accent color
    /// (`PutAccountColorById`), then the dialog dismisses.
    func confirmColorPicker() {
        core.confirmColorPicker()
    }

    /// Dismisses the color picker dialog and tears its headless producer down.
    func closeColorPicker() {
        core.closeColorPicker()
    }

    /// Dismisses the collection / organization "info" dialog and tears its producer down.
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

    /// Confirms the account picker; the shared producer transmits the chosen ownership
    /// back into the create form's ownership sink, then the dialog dismisses.
    func confirmAccountPicker() {
        core.confirmAccountPicker()
    }

    /// Dismisses the account picker dialog and tears its headless producer down.
    func closeAccountPicker() {
        core.closeAccountPicker()
    }
}
