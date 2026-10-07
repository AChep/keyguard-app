import KeyguardShared
@testable import KeyguardUI

@MainActor
final class GpgToolsSourceProbe: GpgToolsSource {
    var changes: [(GpgToolsSnapshot) -> Void] = []
    var results: [(GpgToolsResultSnapshot) -> Void] = []
    var files: [(GpgToolsFilePickerRequest?) -> Void] = []
    var keys: [(GpgToolsPublicKeyRequest?) -> Void] = []
    var exports: [(GpgToolsExportSnapshot?) -> Void] = []
    var validations: [(GpgToolsPublicKeyValidationSnapshot) -> Void] = []
    var operations: [String] = []
    var actions: [String] = []
    var resolvedFiles: [String] = []
    var resolvedFileDetails: [(name: String?, size: Int64)] = []
    var finishedKeys: [String] = []
    var finishedExports: [String] = []
    var cancellations = 0
    var stops = 0
    var closes = 0

    func subscribe(
        operation: String,
        onChange: @escaping (GpgToolsSnapshot) -> Void,
        onResult: @escaping (GpgToolsResultSnapshot) -> Void,
        onFilePicker: @escaping (GpgToolsFilePickerRequest?) -> Void,
        onPublicKey: @escaping (GpgToolsPublicKeyRequest?) -> Void
    ) -> BridgeObservation {
        operations.append(operation)
        changes.append(onChange)
        results.append(onResult)
        files.append(onFilePicker)
        keys.append(onPublicKey)
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }

    func stopGpgTools() { stops += 1 }
    func close() { closes += 1 }
    func resolveGpgToolsFilePicker(id: String, name: String?, size: Int64) {
        resolvedFiles.append(id)
        resolvedFileDetails.append((name, size))
    }
    func addGpgToolsPublicKey() { actions.append("addGpgToolsPublicKey") }
    func removeGpgToolsPublicKey(id: String) { actions.append("removeGpgToolsPublicKey") }
    func validateGpgToolsPublicKey(
        id: String, text: String, onResult: @escaping (GpgToolsPublicKeyValidationSnapshot) -> Void
    ) { validations.append(onResult) }
    func finishGpgToolsPublicKey(id: String, confirm: Bool) { finishedKeys.append(id) }
    func prepareGpgToolsExport(resultId: String, onResult: @escaping (GpgToolsExportSnapshot?) -> Void) {
        exports.append(onResult)
    }
    func finishGpgToolsExport(id: String) { finishedExports.append(id) }
    func dismissGpgToolsResult() { actions.append("dismissGpgToolsResult") }
    func setGpgToolsScope(key: String) { actions.append("setGpgToolsScope") }
    func setGpgToolsSignMode(key: String) { actions.append("setGpgToolsSignMode") }
    func setGpgToolsVerifyMode(key: String) { actions.append("setGpgToolsVerifyMode") }
    func setGpgToolsArmor(value: Bool) { actions.append("setGpgToolsArmor") }
    func setGpgToolsInputText(text: String) { actions.append("input:" + text) }
    func setGpgToolsSignatureText(text: String) { actions.append("setGpgToolsSignatureText") }
    func selectGpgToolsPrivateKey(id: String) { actions.append("selectGpgToolsPrivateKey") }
    func selectGpgToolsEncryptSigningKey(id: String?) { actions.append("selectGpgToolsEncryptSigningKey") }
    func toggleGpgToolsRecipient(id: String) { actions.append("toggleGpgToolsRecipient") }
    func selectGpgToolsInputFile() { actions.append("selectGpgToolsInputFile") }
    func clearGpgToolsInputFile() { actions.append("clearGpgToolsInputFile") }
    func selectGpgToolsSignatureFile() { actions.append("selectGpgToolsSignatureFile") }
    func clearGpgToolsSignatureFile() { actions.append("clearGpgToolsSignatureFile") }
    func runGpgTools() { actions.append("run") }
    func invokeGpgToolsResultCopy() { actions.append("invokeGpgToolsResultCopy") }
}
