import Foundation
import UniformTypeIdentifiers
import KeyguardShared

struct PendingFileExport: Identifiable {
    let id = UUID()
    let requestId: String
    let suggestedName: String
    let resolve: (_ requestId: String, _ uri: String, _ name: String?, _ size: Int64, _ accessToken: String?) -> Void
    let cancel: (_ requestId: String) -> Void
}
