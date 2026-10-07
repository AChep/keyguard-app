import Foundation

struct PendingGpgToolsExport: Identifiable, Sendable {
    let id: String
    let artifactURL: URL
    let name: String
    let observationId: UUID
    let resultId: String
    let lease: GpgToolsExportLease
}
