import SwiftUI
import Observation
import KeyguardShared
import UniformTypeIdentifiers

/// File selection owned by one presentation, with the same platform handling as shared bridge requests.
@MainActor
@Observable
final class FilePickerSession {
    private(set) var pendingFileExport: PendingFileExport?
    private(set) var pendingFilePicker: PendingFilePicker?
    @ObservationIgnored private var pendingResolution: (request: PendingFilePicker, resolve: () -> Void)?
    #if os(macOS)
    @ObservationIgnored private var activePanel: NSSavePanel?
    #endif

    func presentFilePicker(
        for request: AddFilePickerRequest,
        persistent: Bool = false,
        resolveAfterDismissal: Bool = false,
        resolve: @escaping (String, String, String?, Int64, String?) -> Void,
        cancel: @escaping (String) -> Void
    ) {
        #if os(iOS)
        let deferResolution = resolveAfterDismissal
        #else
        // AppKit's modal panel has already closed when it returns its selection.
        let deferResolution = false
        #endif
        present(
            PendingFilePicker(
                requestId: request.requestId,
                kind: request.kind,
                mimeTypes: request.mimeTypes,
                persistent: persistent,
                suggestedName: request.suggestedName,
                resolveAfterDismissal: deferResolution,
                resolve: resolve,
                cancel: cancel
            ))
    }

    func presentKeePassFilePicker(
        for request: KeePassFilePickerRequest,
        resolve: @escaping (String, String, String?, Int64, String?) -> Void,
        cancel: @escaping (String) -> Void
    ) {
        present(
            PendingFilePicker(
                requestId: request.requestId, kind: request.kind, mimeTypes: request.mimeTypes,
                persistent: true, suggestedName: request.suggestedName,
                resolve: resolve, cancel: cancel
            ))
    }

    func present(_ request: PendingFilePicker) {
        enqueue(request)
        #if os(macOS)
        let panel: NSSavePanel
        if request.kind == .theNewDocument {
            let savePanel = NSSavePanel()
            savePanel.canCreateDirectories = true
            if let name = request.suggestedName, !name.isEmpty {
                savePanel.nameFieldStringValue = name
            }
            panel = savePanel
        } else {
            let openPanel = NSOpenPanel()
            let isDirectory = request.kind == .openDirectory
            openPanel.canChooseFiles = !isDirectory
            openPanel.canChooseDirectories = isDirectory
            openPanel.allowsMultipleSelection = false
            openPanel.treatsFilePackagesAsDirectories = false
            panel = openPanel
        }
        panel.allowsOtherFileTypes = false
        // Explicitly configure unrestricted requests too: AppKit can retain a preceding panel's filter.
        panel.allowedContentTypes = request.allowedContentTypes
        activePanel = panel
        let url = panel.runModal() == .OK ? panel.url : nil
        if activePanel === panel { activePanel = nil }
        if let url {
            resolve(result: .success([url]), requestID: request.id)
        } else {
            cancel(requestID: request.id)
        }
        #else
        // Saving/exporting requires a document exporter, not an open picker.
        if request.kind == .theNewDocument && !request.persistent { cancel(requestID: request.id) }
        #endif
    }

    /// Replacing a request cancels its continuation before the new importer becomes visible.
    func enqueue(_ request: PendingFilePicker) {
        cancel()
        pendingFilePicker = request
        if request.kind == .theNewDocument && request.persistent {
            let owner = request.id
            pendingFileExport = PendingFileExport(
                requestId: request.requestId,
                suggestedName: request.suggestedName.flatMap { $0.isEmpty ? nil : $0 } ?? "MyKeyguardDatabase.kdbx",
                resolve: { [weak self] id, uri, name, size, token in
                    guard let self, self.pendingFilePicker?.id == owner else { return }
                    self.pendingFilePicker = nil
                    self.pendingFileExport = nil
                    request.resolve(id, uri, name, size, token)
                },
                cancel: { [weak self] _ in self?.cancel(requestID: owner) }
            )
        }
    }

    func cancel() {
        let pending = pendingFilePicker
        let resolution = pendingResolution
        pendingResolution = nil
        pendingFilePicker = nil
        pendingFileExport = nil
        #if os(macOS)
        let panel = activePanel
        activePanel = nil
        panel?.cancel(nil)
        #endif
        if let resolution { resolution.request.cancel(resolution.request.requestId) }
        if let pending { pending.cancel(pending.requestId) }
    }

    func cancel(requestID: UUID) {
        guard pendingFilePicker?.id == requestID || pendingResolution?.request.id == requestID else { return }
        cancel()
    }

    /// A selected certificate may immediately open a password sheet.
    func pickerDismissed() {
        let resolution = pendingResolution
        pendingResolution = nil
        resolution?.resolve()
    }

    func resolve(result: Result<[URL], Error>, requestID: UUID) {
        guard let pending = pendingFilePicker, pending.id == requestID else { return }
        pendingFilePicker = nil
        pendingFileExport = nil
        guard case let .success(urls) = result, let url = urls.first else {
            pending.cancel(pending.requestId)
            return
        }
        let didAccess = url.startAccessingSecurityScopedResource()
        defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
        if pending.kind == .openDirectory {
            guard let token = url.securityScopedBookmarkToken() else {
                pending.cancel(pending.requestId)
                return
            }
            complete(pending, uri: url.absoluteString, name: url.lastPathComponent, size: -1, token: token)
            return
        }
        #if os(iOS)
        if !pending.persistent {
            // Temporary imports must outlive the security-scoped access window.
            do {
                let dest = try ManagedImportCopy.copy(from: url)
                let file = dest.fileNameAndSize
                complete(pending, uri: dest.absoluteString, name: file.name, size: file.size, token: nil)
            } catch {
                pending.cancel(pending.requestId)
            }
            return
        }
        #endif
        #if os(macOS)
        if pending.kind == .theNewDocument && pending.persistent {
            guard FileManager.default.createFile(atPath: url.path, contents: Data()) else {
                pending.cancel(pending.requestId)
                return
            }
        }
        #endif
        let file = url.fileNameAndSize
        let token = pending.persistent ? url.securityScopedBookmarkToken() : nil
        complete(pending, uri: url.absoluteString, name: file.name, size: file.size, token: token)
    }

    private func complete(_ request: PendingFilePicker, uri: String, name: String?, size: Int64, token: String?) {
        let resolve = { request.resolve(request.requestId, uri, name, size, token) }
        if request.resolveAfterDismissal {
            pendingResolution = (request, resolve)
            return
        }
        resolve()
    }
}
