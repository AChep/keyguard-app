import KeyguardShared

/// One workspace owns its producer and file leases, including completions that arrive after it closes.
@MainActor
protocol GpgToolsSource: AnyObject {
    func subscribe(
        operation: String,
        onChange: @escaping (GpgToolsSnapshot) -> Void,
        onResult: @escaping (GpgToolsResultSnapshot) -> Void,
        onFilePicker: @escaping (GpgToolsFilePickerRequest?) -> Void,
        onPublicKey: @escaping (GpgToolsPublicKeyRequest?) -> Void
    ) -> BridgeObservation
    func stopGpgTools()
    func close()
    func resolveGpgToolsFilePicker(id: String, name: String?, size: Int64)
    func addGpgToolsPublicKey()
    func removeGpgToolsPublicKey(id: String)
    func validateGpgToolsPublicKey(
        id: String, text: String, onResult: @escaping (GpgToolsPublicKeyValidationSnapshot) -> Void)
    func finishGpgToolsPublicKey(id: String, confirm: Bool)
    func prepareGpgToolsExport(resultId: String, onResult: @escaping (GpgToolsExportSnapshot?) -> Void)
    func finishGpgToolsExport(id: String)
    func dismissGpgToolsResult()
    func setGpgToolsScope(key: String)
    func setGpgToolsSignMode(key: String)
    func setGpgToolsVerifyMode(key: String)
    func setGpgToolsArmor(value: Bool)
    func setGpgToolsInputText(text: String)
    func setGpgToolsSignatureText(text: String)
    func selectGpgToolsPrivateKey(id: String)
    func selectGpgToolsEncryptSigningKey(id: String?)
    func toggleGpgToolsRecipient(id: String)
    func selectGpgToolsInputFile()
    func clearGpgToolsInputFile()
    func selectGpgToolsSignatureFile()
    func clearGpgToolsSignatureFile()
    func runGpgTools()
    func invokeGpgToolsResultCopy()
}

extension GpgToolsSession: GpgToolsSource {
    func subscribe(
        operation: String,
        onChange: @escaping (GpgToolsSnapshot) -> Void,
        onResult: @escaping (GpgToolsResultSnapshot) -> Void,
        onFilePicker: @escaping (GpgToolsFilePickerRequest?) -> Void,
        onPublicKey: @escaping (GpgToolsPublicKeyRequest?) -> Void
    ) -> BridgeObservation {
        BridgeObservation(
            observeGpgTools(
                operation: operation, onChange: onChange, onResult: onResult,
                onFilePicker: onFilePicker, onPublicKey: onPublicKey))
    }
}
