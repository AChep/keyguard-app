import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class GpgToolsModel {
    typealias Observer = (
        String,
        @escaping (GpgToolsSnapshot) -> Void,
        @escaping (GpgToolsResultSnapshot) -> Void,
        @escaping (GpgToolsFilePickerRequest?) -> Void,
        @escaping (GpgToolsPublicKeyRequest?) -> Void
    ) -> BridgeObservation

    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observeGpgTools: Observer
    private let stopProducer: () -> Void

    convenience init(core: KeyguardCore) {
        self.init(
            coreProvider: { core },
            observe: {
                BridgeObservation(
                    core.observeGpgTools(operation: $0, onChange: $1, onResult: $2, onFilePicker: $3, onPublicKey: $4))
            },
            stop: { core.stopGpgTools() }
        )
    }

    init(coreProvider: @escaping () -> KeyguardCore, observe: @escaping Observer, stop: @escaping () -> Void) {
        self.coreProvider = coreProvider
        self.observeGpgTools = observe
        self.stopProducer = stop
    }

    private(set) var gpgTools: GpgToolsSnapshot = GpgToolsSnapshot.companion.empty

    private(set) var gpgToolsObservationId = UUID()

    /// `nil` until a run completes; drives the result sheet.
    private(set) var gpgToolsResult: GpgToolsResultSnapshot?

    private(set) var pendingGpgToolsFilePicker: PendingGpgToolsFilePicker?

    private(set) var pendingGpgToolsExport: PendingGpgToolsExport?

    private(set) var pendingGpgToolsPublicKey: PendingGpgToolsPublicKey?

    private(set) var gpgToolsNativeBusy = false

    private(set) var gpgToolsImporting = false

    private(set) var gpgToolsExportSucceeded = false

    private(set) var gpgToolsError: String?

    private(set) var gpgToolsPublicKeyValidation: GpgToolsPublicKeyValidationSnapshot?

    private(set) var gpgToolsPublicKeyValidating = false

    @ObservationIgnored private var gpgToolsSubscription: BridgeObservation?

    @ObservationIgnored private var gpgToolsImportRequests: [String: PendingGpgToolsFilePicker] = [:]

    @ObservationIgnored private var gpgToolsImportTasks: [String: Task<Void, Never>] = [:]

    @ObservationIgnored private var gpgToolsImportingIDs: Set<String> = []

    @ObservationIgnored private var gpgToolsAwaitingFileRequest = false

    @ObservationIgnored private var gpgToolsExportRequests: [String: PendingGpgToolsExport] = [:]

    @ObservationIgnored private var gpgToolsExportTasks: [String: Task<Void, Never>] = [:]

    @ObservationIgnored private var gpgToolsPresentedExports: Set<String> = []

    @ObservationIgnored private var gpgToolsExportPreparationID: UUID?

    @ObservationIgnored private var gpgToolsPublicKeyValidationID = UUID()

    @ObservationIgnored private var gpgToolsOriginalURLs: [URL] = []

    func startGpgToolsObservation(operation: String) {
        stopGpgToolsObservation()
        let observationId = UUID()
        gpgToolsObservationId = observationId
        let coreProvider = self.coreProvider
        gpgToolsSubscription = observeGpgTools(
            operation,
            { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else { return }
                    self.gpgTools = snapshot
                    if !snapshot.loaded { self.resetGpgToolsNativeState() }
                }
            },
            { [weak self] result in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else { return }
                    self.gpgToolsResult = result
                    self.gpgToolsExportSucceeded = false
                    self.gpgToolsError = nil
                }
            },
            { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else {
                        if let request { coreProvider().resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1) }
                        return
                    }
                    self.receiveGpgToolsFilePicker(request, observationId: observationId)
                }
            },
            { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else {
                        if let request { coreProvider().finishGpgToolsPublicKey(id: request.id, confirm: false) }
                        return
                    }
                    self.invalidateGpgToolsPublicKeyValidation()
                    self.pendingGpgToolsPublicKey = request.map { PendingGpgToolsPublicKey(id: $0.id, text: $0.text) }
                }
            }
        )
    }

    func stopGpgToolsObservation() {
        resetGpgToolsNativeState()
        gpgToolsObservationId = UUID()
        stopProducer()
        gpgToolsSubscription?.cancel()
        gpgToolsSubscription = nil
        gpgTools = GpgToolsSnapshot.companion.empty
        gpgToolsResult = nil
        refreshGpgToolsNativeBusy()
    }

    private func resetGpgToolsNativeState() {
        for request in Array(gpgToolsImportRequests.values) { cancelGpgToolsImport(request.id) }
        for task in gpgToolsExportTasks.values { task.cancel() }
        releaseUnpresentedGpgToolsExports()
        // Presented iOS exporters retain their lease until their picker completion
        // or dismissal callback. Mac tasks release only after the IO worker returns.
        pendingGpgToolsFilePicker = nil
        pendingGpgToolsExport = nil
        if let request = pendingGpgToolsPublicKey {
            core.finishGpgToolsPublicKey(id: request.id, confirm: false)
        }
        pendingGpgToolsPublicKey = nil
        invalidateGpgToolsPublicKeyValidation()
        gpgToolsExportPreparationID = nil
        gpgToolsAwaitingFileRequest = false
        gpgToolsOriginalURLs = []
        gpgToolsResult = nil
        gpgToolsError = nil
        gpgToolsExportSucceeded = false
        refreshGpgToolsNativeBusy()
    }

    private func refreshGpgToolsNativeBusy() {
        let imports = gpgToolsImportRequests.values.filter { $0.observationId == gpgToolsObservationId }
        gpgToolsImporting = imports.contains { gpgToolsImportingIDs.contains($0.id) }
        gpgToolsNativeBusy =
            gpgToolsAwaitingFileRequest || !imports.isEmpty || gpgToolsExportPreparationID != nil
            || gpgToolsExportRequests.values.contains { $0.observationId == gpgToolsObservationId }
    }

    private func gpgToolsFileError(_ error: Error, fallback: String) -> String {
        // Provider/Foundation errors already contain localized, actionable details
        // such as denied access, an offline document, or insufficient free space.
        if error is GpgToolsFileError { return fallback }
        return fallback + "\n" + error.localizedDescription
    }

    private func isGpgToolsCancellation(_ error: Error) -> Bool {
        let cocoa = error as NSError
        return error is CancellationError
            || (cocoa.domain == NSCocoaErrorDomain && cocoa.code == CocoaError.Code.userCancelled.rawValue)
    }

    private func receiveGpgToolsFilePicker(_ request: GpgToolsFilePickerRequest?, observationId: UUID) {
        guard let request else {
            // Null also follows a successfully resolved earlier request. Teardown
            // is handled by stop/the empty locked snapshot, so a queued null must
            // not cancel a newer picker or an import that still holds an IO lease.
            return
        }
        gpgToolsAwaitingFileRequest = false
        guard gpgToolsImportRequests[request.id] == nil else { return }
        guard !gpgToolsImportRequests.values.contains(where: { $0.observationId == observationId }) else {
            core.resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1)
            refreshGpgToolsNativeBusy()
            return
        }
        guard let destination = URL(string: request.destinationUri), destination.isFileURL else {
            core.resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1)
            gpgToolsError = L10n.gpgKeyImportErrorRead
            refreshGpgToolsNativeBusy()
            return
        }
        let pending = PendingGpgToolsFilePicker(
            id: request.id, destinationURL: destination, observationId: observationId)
        gpgToolsImportRequests[pending.id] = pending
        gpgToolsError = nil
        #if os(macOS)
        gpgToolsImportTasks[pending.id] = Task { [self] in
            do {
                if let source = try await GpgToolsFileSupport.chooseInput() {
                    await performGpgToolsImport(pending, source: source)
                } else {
                    finishGpgToolsImport(pending, result: .success(nil))
                }
            } catch {
                finishGpgToolsImport(pending, result: .failure(error))
            }
        }
        #else
        pendingGpgToolsFilePicker = pending
        #endif
        refreshGpgToolsNativeBusy()
    }

    func completeGpgToolsFilePicker(id: String, result: Result<URL?, Error>) {
        guard let request = gpgToolsImportRequests[id], gpgToolsImportTasks[id] == nil else { return }
        if pendingGpgToolsFilePicker?.id == id { pendingGpgToolsFilePicker = nil }
        guard request.observationId == gpgToolsObservationId else {
            finishGpgToolsImport(request, result: .success(nil))
            return
        }
        switch result {
        case .success(let source?):
            gpgToolsImportTasks[id] = Task { [self] in await performGpgToolsImport(request, source: source) }
        case .success(nil): finishGpgToolsImport(request, result: .success(nil))
        case .failure(let error): finishGpgToolsImport(request, result: .failure(error))
        }
    }

    func cancelGpgToolsFilePicker(id: String) {
        // Successful selection dismisses the picker while staging continues.
        guard pendingGpgToolsFilePicker?.id == id else { return }
        cancelGpgToolsImport(id)
    }

    private func cancelGpgToolsImport(_ id: String) {
        guard let request = gpgToolsImportRequests[id] else { return }
        if pendingGpgToolsFilePicker?.id == id { pendingGpgToolsFilePicker = nil }
        if let task = gpgToolsImportTasks[id] {
            task.cancel()
        } else {
            finishGpgToolsImport(request, result: .success(nil))
        }
    }

    func performGpgToolsImport(_ request: PendingGpgToolsFilePicker, source: URL) async {
        gpgToolsImportingIDs.insert(request.id)
        refreshGpgToolsNativeBusy()
        do {
            let file = try await GpgToolsFileSupport.importFile(from: source, to: request.destinationURL)
            try Task.checkCancellation()
            finishGpgToolsImport(request, result: .success(file))
        } catch {
            finishGpgToolsImport(request, result: .failure(error))
        }
    }

    private func finishGpgToolsImport(
        _ request: PendingGpgToolsFilePicker, result: Result<GpgToolsImportedFile?, Error>
    ) {
        guard gpgToolsImportRequests.removeValue(forKey: request.id) != nil else { return }
        gpgToolsImportTasks[request.id] = nil
        gpgToolsImportingIDs.remove(request.id)
        if pendingGpgToolsFilePicker?.id == request.id { pendingGpgToolsFilePicker = nil }
        var file: GpgToolsImportedFile?
        if request.observationId == gpgToolsObservationId {
            switch result {
            case .success(let value):
                file = value
                if let value { gpgToolsOriginalURLs.append(value.originalURL) }
            case .failure(let error):
                if !isGpgToolsCancellation(error) {
                    gpgToolsError = gpgToolsFileError(error, fallback: L10n.gpgKeyImportErrorRead)
                }
            }
        }
        // Releasing this request allows Kotlin to delete staging: do it only
        // after importFile has awaited the actual coordinated IO worker.
        core.resolveGpgToolsFilePicker(id: request.id, name: file?.displayName, size: file?.byteCount ?? -1)
        refreshGpgToolsNativeBusy()
    }

    func setGpgToolsScope(_ key: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsScope(key: key)
    }

    func setGpgToolsSignMode(_ key: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsSignMode(key: key)
    }

    func setGpgToolsVerifyMode(_ key: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsVerifyMode(key: key)
    }

    func setGpgToolsArmor(_ value: Bool) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsArmor(value: value)
    }

    func setGpgToolsInputText(_ text: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsInputText(text: text)
    }

    /// Writes the detached-signature text (verify + detached mode only).
    func setGpgToolsSignatureText(_ text: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.setGpgToolsSignatureText(text: text)
    }

    func selectGpgToolsPrivateKey(_ id: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.selectGpgToolsPrivateKey(id: id)
    }

    /// Selects the key to sign an encrypted message with, or `nil` for "do not sign".
    func selectGpgToolsEncryptSigningKey(_ id: String?) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.selectGpgToolsEncryptSigningKey(id: id)
    }

    func toggleGpgToolsRecipient(_ id: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.toggleGpgToolsRecipient(id: id)
    }

    /// Runs the configured GPG operation; the outcome arrives on the result channel.
    func runGpgTools() {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsError = nil
        core.runGpgTools()
    }

    func invokeGpgToolsResultCopy() {
        core.invokeGpgToolsResultCopy()
    }

    func selectGpgToolsInputFile() {
        guard gpgTools.loaded, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsAwaitingFileRequest = true
        refreshGpgToolsNativeBusy()
        core.selectGpgToolsInputFile()
    }

    func clearGpgToolsInputFile() {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.clearGpgToolsInputFile()
    }

    func selectGpgToolsSignatureFile() {
        guard gpgTools.loaded, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsAwaitingFileRequest = true
        refreshGpgToolsNativeBusy()
        core.selectGpgToolsSignatureFile()
    }

    func clearGpgToolsSignatureFile() {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.clearGpgToolsSignatureFile()
    }

    func addGpgToolsPublicKey() {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.addGpgToolsPublicKey()
    }

    func removeGpgToolsPublicKey(_ id: String) {
        guard !gpgToolsNativeBusy, !gpgTools.busy else { return }
        core.removeGpgToolsPublicKey(id: id)
    }

    func invalidateGpgToolsPublicKeyValidation() {
        gpgToolsPublicKeyValidationID = UUID()
        gpgToolsPublicKeyValidation = nil
        gpgToolsPublicKeyValidating = false
    }

    func validateGpgToolsPublicKey(id: String, text: String) {
        guard pendingGpgToolsPublicKey?.id == id else { return }
        invalidateGpgToolsPublicKeyValidation()
        let validationId = gpgToolsPublicKeyValidationID
        let observationId = gpgToolsObservationId
        gpgToolsPublicKeyValidating = true
        core.validateGpgToolsPublicKey(id: id, text: text) { [weak self] validation in
            Task { @MainActor [weak self] in
                guard let self, self.gpgToolsObservationId == observationId,
                    self.pendingGpgToolsPublicKey?.id == id,
                    self.gpgToolsPublicKeyValidationID == validationId
                else { return }
                self.gpgToolsPublicKeyValidation = validation
                self.gpgToolsPublicKeyValidating = false
            }
        }
    }

    func finishGpgToolsPublicKey(id: String, confirm: Bool) {
        guard pendingGpgToolsPublicKey?.id == id else { return }
        if confirm {
            guard !gpgToolsPublicKeyValidating,
                let validation = gpgToolsPublicKeyValidation,
                validation.error == nil, !validation.keys.isEmpty
            else { return }
        }
        pendingGpgToolsPublicKey = nil
        invalidateGpgToolsPublicKeyValidation()
        core.finishGpgToolsPublicKey(id: id, confirm: confirm)
    }

    func invokeGpgToolsResultSave() {
        guard let result = gpgToolsResult, result.canSave, !gpgToolsNativeBusy else { return }
        let preparationId = UUID()
        let observationId = gpgToolsObservationId
        let core = self.core
        gpgToolsExportPreparationID = preparationId
        gpgToolsError = nil
        gpgToolsExportSucceeded = false
        refreshGpgToolsNativeBusy()
        core.prepareGpgToolsExport(resultId: result.id) { [weak self] export in
            Task { @MainActor [weak self] in
                guard let self, self.gpgToolsObservationId == observationId,
                    self.gpgToolsResult?.id == result.id,
                    self.gpgToolsExportPreparationID == preparationId
                else {
                    if let export { core.finishGpgToolsExport(id: export.id) }
                    return
                }
                self.gpgToolsExportPreparationID = nil
                guard let export, let url = URL(string: export.uri), url.isFileURL else {
                    if let export { core.finishGpgToolsExport(id: export.id) }
                    self.gpgToolsError = L10n.gpgToolsExportFailed
                    self.refreshGpgToolsNativeBusy()
                    return
                }
                let pending = PendingGpgToolsExport(
                    id: export.id, artifactURL: url, name: export.name,
                    observationId: observationId, resultId: result.id
                )
                self.gpgToolsExportRequests[pending.id] = pending
                #if os(macOS)
                let originals = self.gpgToolsOriginalURLs
                self.gpgToolsExportTasks[pending.id] = Task { [self] in
                    do {
                        let saved = try await GpgToolsFileSupport.exportFile(
                            pending.artifactURL, suggestedName: pending.name, excluding: originals
                        )
                        self.completeGpgToolsExport(id: pending.id, result: .success(saved))
                    } catch {
                        self.completeGpgToolsExport(id: pending.id, result: .failure(error))
                    }
                }
                #else
                self.pendingGpgToolsExport = pending
                #endif
                self.refreshGpgToolsNativeBusy()
            }
        }
    }

    func completeGpgToolsExport(id: String, result: Result<Bool, Error>) {
        guard let request = gpgToolsExportRequests.removeValue(forKey: id) else { return }
        gpgToolsExportTasks[id] = nil
        gpgToolsPresentedExports.remove(id)
        if pendingGpgToolsExport?.id == id { pendingGpgToolsExport = nil }
        core.finishGpgToolsExport(id: id)
        if request.observationId == gpgToolsObservationId, gpgToolsResult?.id == request.resultId {
            switch result {
            case .success(let saved): gpgToolsExportSucceeded = saved
            case .failure(let error):
                if !isGpgToolsCancellation(error) {
                    gpgToolsError = gpgToolsFileError(error, fallback: L10n.gpgToolsExportFailed)
                }
            }
        }
        refreshGpgToolsNativeBusy()
    }

    func cancelGpgToolsExport(id: String) {
        if let task = gpgToolsExportTasks[id] {
            task.cancel()
        } else {
            completeGpgToolsExport(id: id, result: .success(false))
        }
    }

    func gpgToolsExportDidPresent(id: String) {
        guard gpgToolsExportRequests[id] != nil else { return }
        gpgToolsPresentedExports.insert(id)
    }

    private func releaseUnpresentedGpgToolsExports() {
        for id in Array(gpgToolsExportRequests.keys)
        where gpgToolsExportTasks[id] == nil && !gpgToolsPresentedExports.contains(id) {
            completeGpgToolsExport(id: id, result: .success(false))
        }
    }

    func dismissGpgToolsResult() {
        gpgToolsExportPreparationID = nil
        for task in gpgToolsExportTasks.values { task.cancel() }
        releaseUnpresentedGpgToolsExports()
        pendingGpgToolsExport = nil
        core.dismissGpgToolsResult()
        gpgToolsResult = nil
        gpgToolsError = nil
        gpgToolsExportSucceeded = false
        refreshGpgToolsNativeBusy()
    }
}
