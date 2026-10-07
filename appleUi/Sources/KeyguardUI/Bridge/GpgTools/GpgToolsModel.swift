import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class GpgToolsModel {
    private let source: any GpgToolsSource
    private let lifetime: BridgeObservation
    private var closed: Bool { lifetime.isCancelled }

    init(source: any GpgToolsSource) {
        self.source = source
        self.lifetime = BridgeObservation { source.close() }
    }

    func close() {
        guard !closed else { return }
        stopGpgToolsObservation()
        lifetime.cancel()
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

    @ObservationIgnored private var gpgToolsExportPreparationID: UUID?

    @ObservationIgnored private var gpgToolsPublicKeyValidationID = UUID()

    @ObservationIgnored private var gpgToolsOriginalURLs: [URL] = []

    func startGpgToolsObservation(operation: String) {
        guard !closed else { return }
        stopGpgToolsObservation()
        let observationId = UUID()
        gpgToolsObservationId = observationId
        let source = self.source
        gpgToolsSubscription = source.subscribe(
            operation: operation,
            onChange: { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else { return }
                    self.gpgTools = snapshot
                    if !snapshot.loaded { self.resetGpgToolsNativeState() }
                }
            },
            onResult: { [weak self] result in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else { return }
                    self.gpgToolsResult = result
                    self.gpgToolsExportSucceeded = false
                    self.gpgToolsError = nil
                }
            },
            onFilePicker: { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else {
                        if let request { source.resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1) }
                        return
                    }
                    self.receiveGpgToolsFilePicker(request, observationId: observationId)
                }
            },
            onPublicKey: { [weak self] request in
                Task { @MainActor [weak self] in
                    guard let self, self.gpgToolsObservationId == observationId else {
                        if let request { source.finishGpgToolsPublicKey(id: request.id, confirm: false) }
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
        source.stopGpgTools()
        gpgToolsSubscription?.cancel()
        gpgToolsSubscription = nil
        gpgTools = GpgToolsSnapshot.companion.empty
        gpgToolsResult = nil
        refreshGpgToolsNativeBusy()
    }

    private func resetGpgToolsNativeState() {
        for request in Array(gpgToolsImportRequests.values) { cancelGpgToolsImport(request.id) }
        for task in gpgToolsExportTasks.values { task.cancel() }
        releaseGpgToolsExportPresentations()
        pendingGpgToolsFilePicker = nil
        if let request = pendingGpgToolsPublicKey {
            source.finishGpgToolsPublicKey(id: request.id, confirm: false)
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
            source.resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1)
            refreshGpgToolsNativeBusy()
            return
        }
        guard let destination = URL(string: request.destinationUri), destination.isFileURL else {
            source.resolveGpgToolsFilePicker(id: request.id, name: nil, size: -1)
            gpgToolsError = L10n.keyImportErrorRead
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
                    gpgToolsError = gpgToolsFileError(error, fallback: L10n.keyImportErrorRead)
                }
            }
        }
        // Releasing this request allows Kotlin to delete staging: do it only
        // after importFile has awaited the actual coordinated IO worker.
        source.resolveGpgToolsFilePicker(id: request.id, name: file?.displayName, size: file?.byteCount ?? -1)
        refreshGpgToolsNativeBusy()
    }

    func setGpgToolsScope(_ key: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsScope(key: key)
    }

    func setGpgToolsSignMode(_ key: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsSignMode(key: key)
    }

    func setGpgToolsVerifyMode(_ key: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsVerifyMode(key: key)
    }

    func setGpgToolsArmor(_ value: Bool) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsArmor(value: value)
    }

    func setGpgToolsInputText(_ text: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsInputText(text: text)
    }

    /// Writes the detached-signature text (verify + detached mode only).
    func setGpgToolsSignatureText(_ text: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.setGpgToolsSignatureText(text: text)
    }

    func selectGpgToolsPrivateKey(_ id: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.selectGpgToolsPrivateKey(id: id)
    }

    /// Selects the key to sign an encrypted message with, or `nil` for "do not sign".
    func selectGpgToolsEncryptSigningKey(_ id: String?) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.selectGpgToolsEncryptSigningKey(id: id)
    }

    func toggleGpgToolsRecipient(_ id: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.toggleGpgToolsRecipient(id: id)
    }

    /// Runs the configured GPG operation; the outcome arrives on the result channel.
    func runGpgTools() {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsError = nil
        source.runGpgTools()
    }

    func invokeGpgToolsResultCopy() {
        source.invokeGpgToolsResultCopy()
    }

    func selectGpgToolsInputFile() {
        guard gpgTools.loaded, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsAwaitingFileRequest = true
        refreshGpgToolsNativeBusy()
        source.selectGpgToolsInputFile()
    }

    func clearGpgToolsInputFile() {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.clearGpgToolsInputFile()
    }

    func selectGpgToolsSignatureFile() {
        guard gpgTools.loaded, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        gpgToolsAwaitingFileRequest = true
        refreshGpgToolsNativeBusy()
        source.selectGpgToolsSignatureFile()
    }

    func clearGpgToolsSignatureFile() {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.clearGpgToolsSignatureFile()
    }

    func addGpgToolsPublicKey() {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.addGpgToolsPublicKey()
    }

    func removeGpgToolsPublicKey(_ id: String) {
        guard !closed, !gpgToolsNativeBusy, !gpgTools.busy else { return }
        source.removeGpgToolsPublicKey(id: id)
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
        source.validateGpgToolsPublicKey(id: id, text: text) { [weak self] validation in
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
        source.finishGpgToolsPublicKey(id: id, confirm: confirm)
    }

    func invokeGpgToolsResultSave() {
        guard let result = gpgToolsResult, result.canSave, !gpgToolsNativeBusy else { return }
        let preparationId = UUID()
        let observationId = gpgToolsObservationId
        let source = self.source
        gpgToolsExportPreparationID = preparationId
        gpgToolsError = nil
        gpgToolsExportSucceeded = false
        refreshGpgToolsNativeBusy()
        source.prepareGpgToolsExport(resultId: result.id) { [weak self] export in
            Task { @MainActor [weak self] in
                guard let self, self.gpgToolsObservationId == observationId,
                    self.gpgToolsResult?.id == result.id,
                    self.gpgToolsExportPreparationID == preparationId
                else {
                    if let export { source.finishGpgToolsExport(id: export.id) }
                    return
                }
                self.gpgToolsExportPreparationID = nil
                guard let export, let url = URL(string: export.uri), url.isFileURL else {
                    if let export { source.finishGpgToolsExport(id: export.id) }
                    self.gpgToolsError = L10n.gpgToolsExportFailed
                    self.refreshGpgToolsNativeBusy()
                    return
                }
                let pending = PendingGpgToolsExport(
                    id: export.id, artifactURL: url, name: export.name,
                    observationId: observationId, resultId: result.id,
                    lease: GpgToolsExportLease { source.finishGpgToolsExport(id: export.id) }
                )
                self.gpgToolsExportRequests[pending.id] = pending
                #if os(macOS)
                let originals = self.gpgToolsOriginalURLs
                self.gpgToolsExportTasks[pending.id] = Task { [self] in
                    do {
                        let saved = try await GpgToolsFileSupport.exportFile(
                            pending.artifactURL, suggestedName: pending.name, excluding: originals
                        )
                        self.completeGpgToolsExport(pending, result: .success(saved))
                    } catch {
                        self.completeGpgToolsExport(pending, result: .failure(error))
                    }
                }
                #else
                self.pendingGpgToolsExport = pending
                #endif
                self.refreshGpgToolsNativeBusy()
            }
        }
    }

    func completeGpgToolsExport(_ request: PendingGpgToolsExport, result: Result<Bool, Error>) {
        request.lease.release()
        let id = request.id
        guard gpgToolsExportRequests.removeValue(forKey: id) != nil else { return }
        gpgToolsExportTasks[id] = nil
        if pendingGpgToolsExport?.id == id { pendingGpgToolsExport = nil }
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

    private func releaseGpgToolsExportPresentations() {
        // SwiftUI's callbacks and Transferable retain the request while an iOS
        // export is active. Mac tasks retain it until coordinated IO returns.
        gpgToolsExportRequests = gpgToolsExportRequests.filter { gpgToolsExportTasks[$0.key] != nil }
        pendingGpgToolsExport = nil
    }

    func dismissGpgToolsResult() {
        gpgToolsExportPreparationID = nil
        for task in gpgToolsExportTasks.values { task.cancel() }
        releaseGpgToolsExportPresentations()
        source.dismissGpgToolsResult()
        gpgToolsResult = nil
        gpgToolsError = nil
        gpgToolsExportSucceeded = false
        refreshGpgToolsNativeBusy()
    }
}
