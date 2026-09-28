import Foundation
import UniformTypeIdentifiers
import KeyguardShared

#if os(iOS)
struct PendingFilePicker: Identifiable {
    let id = UUID()
    let requestId: String
    let kind: AddFilePickerKind
    let mimeTypes: [String]
    let persistent: Bool
    var presentsInAddForm = false
    var presentsInBackupSetup = false
    var presentsInKeePassLogin = false
    let resolve: (_ requestId: String, _ uri: String, _ name: String?, _ size: Int64, _ accessToken: String?) -> Void
    let cancel: (_ requestId: String) -> Void

    /// The content types the system importer should allow, mapped from the
    /// request's MIME types (folder selection falls back to `.folder`).
    var allowedContentTypes: [UTType] {
        if kind == AddFilePickerKind.openDirectory {
            return [.folder]
        }
        let types = FilePickerContentTypes.contentTypes(forMimeTypes: mimeTypes)
        return types.isEmpty ? [.data, .item] : types
    }
}
#endif
