import Foundation

struct ToastItem: Identifiable {
    let id: String
    let type: String?
    let title: String
    let text: String?
    var isError: Bool { type == "ERROR" }
    /// The line a single-row toast shows: the detail if present, else title.
    var line: String {
        if let text, !text.isEmpty { return text }
        return title
    }
}
