import KeyguardShared

/// Shared rendering for app dialogs and dialogs owned by an editor.
@MainActor
protocol FormDialogsSource: AnyObject {
    func observeConfirmation(onChange: @escaping (ConfirmationSnapshot?) -> Void) -> KeyguardCancellable
    func setConfirmationItemBoolean(key: String, value: Bool)
    func setConfirmationItemString(key: String, text: String)
    func selectConfirmationItemEnum(key: String, optionKey: String)
    func addConfirmationItem()
    func removeConfirmationItem(key: String)
    func selectConfirmationItemFile(key: String)
    func openConfirmationItemDoc(key: String)
    func clearConfirmationItemFile(key: String)
    func confirmConfirmation()
    func closeConfirmation()
    func observeCipherLinkPicker(onChange: @escaping (CipherLinkPickerSnapshot?) -> Void) -> KeyguardCancellable
    func setCipherLinkPickerQuery(text: String)
    func selectCipherLinkPickerItem(id: String)
    func closeCipherLinkPicker()
    func observeAccountPicker(onChange: @escaping (AccountPickerSnapshot?) -> Void) -> KeyguardCancellable
    func setAccountPickerNewFolderName(text: String)
    func selectAccountPickerItem(key: String)
    func confirmAccountPicker()
    func closeAccountPicker()
}

extension KeyguardCore: FormDialogsSource {}
extension FormDialogsSession: FormDialogsSource {}
