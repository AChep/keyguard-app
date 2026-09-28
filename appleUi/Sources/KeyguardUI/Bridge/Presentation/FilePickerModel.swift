import SwiftUI
import Observation
import KeyguardShared
import UniformTypeIdentifiers

@MainActor
@Observable
final class FilePickerModel {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    @ObservationIgnored private var started = false
    @ObservationIgnored private var backupSetupPickerSession: UUID?

    /// Routes folder requests from the shared add-form bridge to the backup wizard.
    func beginBackupSetupPickerSession() {
        endBackupSetupPickerSession()
        backupSetupPickerSession = UUID()
    }

    /// Invalidates queued callbacks and cancels only the wizard's pending picker.
    func endBackupSetupPickerSession() {
        backupSetupPickerSession = nil
        #if os(iOS)
        if let pending = pendingFilePicker, pending.presentsInBackupSetup {
            pendingFilePicker = nil
            pending.cancel(pending.requestId)
        }
        #endif
    }

    func start() {
        guard !started else { return }
        started = true
        // A running create form's DateTime row (the Send custom deletion / expiration
        // date) surfaces a native date / time picker request; present a SwiftUI sheet.
        core.setAddDatePickerRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.pendingDatePicker = PendingDatePicker(request)
            }
        }
        // Present a native open/save panel whenever a running create form needs a
        // file (attachment upload, SSH-key import, File Send) and route the choice
        // back into the shared producer.
        core.setAddFilePickerRequestHandler { [weak self] request in
            // Capture ownership before the hop: a dismissed wizard must not turn
            // its queued folder request into an orphaned add-form request.
            let backupSession = request.kind == .openDirectory ? self?.backupSetupPickerSession : nil
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let backupSession, self.backupSetupPickerSession != backupSession {
                    self.core.cancelAddFilePicker(requestId: request.requestId)
                    return
                }
                self.presentFilePicker(
                    for: request,
                    presentsInAddForm: backupSession == nil,
                    presentsInBackupSetup: backupSession != nil,
                    resolve: { [weak self] requestId, uri, name, size, accessToken in
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            if let backupSession, self.backupSetupPickerSession != backupSession {
                                self.core.cancelAddFilePicker(requestId: requestId)
                                return
                            }
                            self.core.resolveAddFilePicker(
                                requestId: requestId, uri: uri, name: name, size: size, accessToken: accessToken)
                        }
                    },
                    cancel: { [weak self] requestId in
                        Task { @MainActor [weak self] in
                            self?.core.cancelAddFilePicker(requestId: requestId)
                        }
                    }
                )
            }
        }
        // A confirmation dialog FILE item ("choose file", e.g. wordlist import)
        // uses the same native panel, resolving into the confirmation producer.
        core.setConfirmationFilePickerRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.presentFilePicker(
                    for: request,
                    resolve: { [weak self] requestId, uri, name, size, _ in
                        Task { @MainActor [weak self] in
                            self?.core.resolveConfirmationFilePicker(
                                requestId: requestId,
                                uri: uri,
                                name: name,
                                size: size
                            )
                        }
                    },
                    cancel: { [weak self] requestId in
                        Task { @MainActor [weak self] in
                            self?.core.cancelConfirmationFilePicker(requestId: requestId)
                        }
                    }
                )
            }
        }
        core.setKeePassFilePickerRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.presentKeePassFilePicker(for: request)
            }
        }
    }

    var pendingDatePicker: PendingDatePicker?

    #if os(iOS)
    var pendingFilePicker: PendingFilePicker?
    #endif

    #if os(iOS)
    var pendingFileExport: PendingFileExport?
    #endif

    /// Confirms an in-flight create-form date / time picker with the chosen value
    /// (date requests read year/month/day; time requests read hour/minute), then clears
    /// the pending request.
    func resolveDatePicker(year: Int32, month: Int32, day: Int32, hour: Int32, minute: Int32) {
        guard let pending = pendingDatePicker else { return }
        pendingDatePicker = nil
        core.resolveAddDatePicker(
            requestId: pending.request.requestId,
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute
        )
    }

    /// Cancels the in-flight create-form date / time picker, then clears the request.
    func cancelDatePicker() {
        guard let pending = pendingDatePicker else { return }
        pendingDatePicker = nil
        core.cancelAddDatePicker(requestId: pending.request.requestId)
    }

    func presentFilePicker(
        for request: AddFilePickerRequest,
        presentsInAddForm: Bool = false,
        presentsInBackupSetup: Bool = false,
        resolve:
            @escaping (_ requestId: String, _ uri: String, _ name: String?, _ size: Int64, _ accessToken: String?) ->
            Void,
        cancel: @escaping (_ requestId: String) -> Void
    ) {
        #if os(macOS)
        let url: URL?
        if request.kind == AddFilePickerKind.theNewDocument {
            let panel = NSSavePanel()
            panel.canCreateDirectories = true
            let types = FilePickerContentTypes.contentTypes(forMimeTypes: request.mimeTypes)
            panel.allowedContentTypes = types.isEmpty ? [.data, .item] : types
            panel.allowsOtherFileTypes = false
            if let name = request.suggestedName, !name.isEmpty {
                panel.nameFieldStringValue = name
            }
            url = panel.runModal() == .OK ? panel.url : nil
        } else {
            let panel = NSOpenPanel()
            let isDirectory = request.kind == AddFilePickerKind.openDirectory
            panel.canChooseFiles = !isDirectory
            panel.canChooseDirectories = isDirectory
            panel.allowsMultipleSelection = false
            panel.treatsFilePackagesAsDirectories = false
            panel.allowsOtherFileTypes = false
            let types = FilePickerContentTypes.contentTypes(forMimeTypes: request.mimeTypes)
            // AppKit can retain the preceding panel's application-only filter.
            // Explicitly configure unrestricted requests as well as typed ones.
            panel.allowedContentTypes = isDirectory ? [.folder] : (types.isEmpty ? [.data, .item] : types)
            url = panel.runModal() == .OK ? panel.url : nil
        }
        if let url = url {
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
            let token = request.kind == AddFilePickerKind.openDirectory ? url.securityScopedBookmarkToken() : nil
            if request.kind == AddFilePickerKind.openDirectory && token == nil {
                cancel(request.requestId)
                return
            }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .nameKey])
            let size = Int64(values?.fileSize ?? -1)
            let name = values?.name ?? url.lastPathComponent
            resolve(request.requestId, url.absoluteString, name, size, token)
        } else {
            cancel(request.requestId)
        }
        #else
        // iOS has no NSOpenPanel: bubble the request up to a SwiftUI `.fileImporter`
        // on the requesting form or root, carrying the continuation so the choice
        // resolves back into whichever caller raised it.
        if request.kind == AddFilePickerKind.theNewDocument {
            // A "save / export" target — `.fileImporter` only opens existing items.
            // No add/edit form path emits this on iOS (export has its own screen),
            // so cancel rather than mis-present an open panel.
            cancel(request.requestId)
        } else {
            pendingFilePicker = PendingFilePicker(
                requestId: request.requestId,
                kind: request.kind,
                mimeTypes: request.mimeTypes,
                persistent: false,
                presentsInAddForm: presentsInAddForm,
                presentsInBackupSetup: presentsInBackupSetup,
                resolve: resolve,
                cancel: cancel
            )
        }
        #endif
    }

    func presentKeePassFilePicker(for request: KeePassFilePickerRequest) {
        #if os(macOS)
        if request.kind == AddFilePickerKind.theNewDocument {
            let panel = NSSavePanel()
            panel.canCreateDirectories = true
            let types = FilePickerContentTypes.contentTypes(forMimeTypes: request.mimeTypes)
            panel.allowedContentTypes = types.isEmpty ? [.data, .item] : types
            panel.allowsOtherFileTypes = false
            if let name = request.suggestedName, !name.isEmpty {
                panel.nameFieldStringValue = name
            }
            guard panel.runModal() == .OK, let url = panel.url,
                FileManager.default.createFile(atPath: url.path, contents: Data())
            else {
                core.cancelKeePassFilePicker(requestId: request.requestId)
                return
            }
            let token = url.securityScopedBookmarkToken()
            core.resolveKeePassFilePicker(
                requestId: request.requestId,
                uri: url.absoluteString,
                name: url.lastPathComponent,
                size: 0,
                accessToken: token
            )
        } else {
            let panel = NSOpenPanel()
            let isDirectory = request.kind == AddFilePickerKind.openDirectory
            panel.canChooseFiles = !isDirectory
            panel.canChooseDirectories = isDirectory
            panel.allowsMultipleSelection = false
            panel.treatsFilePackagesAsDirectories = false
            panel.allowsOtherFileTypes = false
            let types = FilePickerContentTypes.contentTypes(forMimeTypes: request.mimeTypes)
            // AppKit can retain the preceding panel's application-only filter.
            // Explicitly configure unrestricted requests as well as typed ones.
            panel.allowedContentTypes = isDirectory ? [.folder] : (types.isEmpty ? [.data, .item] : types)
            guard panel.runModal() == .OK, let url = panel.url else {
                core.cancelKeePassFilePicker(requestId: request.requestId)
                return
            }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .nameKey])
            let size = Int64(values?.fileSize ?? -1)
            let name = values?.name ?? url.lastPathComponent
            let token = url.securityScopedBookmarkToken()
            core.resolveKeePassFilePicker(
                requestId: request.requestId,
                uri: url.absoluteString,
                name: name,
                size: size,
                accessToken: token
            )
        }
        #else
        if request.kind == AddFilePickerKind.theNewDocument {
            // `.fileImporter` only opens existing items; drive a document EXPORT
            // picker instead, which moves a pre-created empty kdbx to the spot
            // the user chooses (mirrors the Compose iOS FilePickerEffect).
            let fallbackName = "MyKeyguardDatabase.kdbx"
            let suggestedName = request.suggestedName.flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
            pendingFileExport = PendingFileExport(
                requestId: request.requestId,
                suggestedName: suggestedName,
                resolve: { [weak self] requestId, uri, name, size, token in
                    Task { @MainActor [weak self] in
                        self?.pendingFileExport = nil
                        self?.core.resolveKeePassFilePicker(
                            requestId: requestId, uri: uri, name: name, size: size, accessToken: token
                        )
                    }
                },
                cancel: { [weak self] requestId in
                    Task { @MainActor [weak self] in
                        self?.pendingFileExport = nil
                        self?.core.cancelKeePassFilePicker(requestId: requestId)
                    }
                }
            )
        } else {
            pendingFilePicker = PendingFilePicker(
                requestId: request.requestId,
                kind: request.kind,
                mimeTypes: request.mimeTypes,
                persistent: true,
                presentsInKeePassLogin: true,
                resolve: { [weak self] requestId, uri, name, size, token in
                    Task { @MainActor [weak self] in
                        self?.core.resolveKeePassFilePicker(
                            requestId: requestId, uri: uri, name: name, size: size, accessToken: token
                        )
                    }
                },
                cancel: { [weak self] requestId in
                    Task { @MainActor [weak self] in
                        self?.core.cancelKeePassFilePicker(requestId: requestId)
                    }
                }
            )
        }
        #endif
    }

    #if os(iOS)
    func resolveFilePicker(result: Result<[URL], Error>, requestId: String? = nil) {
        guard let pending = pendingFilePicker else { return }
        // A dismissed importer's completion must not consume a newer request.
        if let requestId, pending.requestId != requestId { return }
        pendingFilePicker = nil
        switch result {
        case let .success(urls):
            guard let url = urls.first else {
                pending.cancel(pending.requestId)
                return
            }
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }

            if pending.kind == AddFilePickerKind.openDirectory {
                let values = try? url.resourceValues(forKeys: [.nameKey])
                let name = values?.name ?? url.lastPathComponent
                guard let token = url.securityScopedBookmarkToken() else {
                    pending.cancel(pending.requestId)
                    return
                }
                pending.resolve(pending.requestId, url.absoluteString, name, -1, token)
                return
            }

            if pending.persistent {
                // The shared code keeps reading and writing this file for the
                // lifetime of a KeePass account, so hand over the ORIGINAL url
                // plus a security-scoped bookmark token — never a temp copy.
                let name = (try? url.resourceValues(forKeys: [.nameKey]))?.name ?? url.lastPathComponent
                let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1)
                let token = url.securityScopedBookmarkToken()
                pending.resolve(pending.requestId, url.absoluteString, name, size, token)
                return
            }

            // Copy into a unique temp directory so the original name is preserved and
            // the file outlives the security-scoped access window.
            let name = (try? url.resourceValues(forKeys: [.nameKey]))?.name ?? url.lastPathComponent
            let tempDir = FileManager.default.temporaryDirectory
                .appendingPathComponent("keyguard-import", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            let dest = tempDir.appendingPathComponent(name)
            do {
                try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: url, to: dest)
                let size = Int64((try? dest.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1)
                pending.resolve(pending.requestId, dest.absoluteString, name, size, nil)
            } catch {
                pending.cancel(pending.requestId)
            }
        case .failure:
            pending.cancel(pending.requestId)
        }
    }
    #endif
}
