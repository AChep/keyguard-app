import Foundation

/// Validates a downloaded file before handing it to the native document UI.
@MainActor
final class FileOpeningCoordinator {
    private let present: (URL) -> Bool
    private let showFailure: () -> Void

    init(present: @escaping (URL) -> Bool, showFailure: @escaping () -> Void) {
        self.present = present
        self.showFailure = showFailure
    }

    func open(_ raw: String) {
        let url = raw.hasPrefix("/") ? URL(fileURLWithPath: raw) : URL(string: raw)
        guard let url, url.isFileURL,
            (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true,
            FileManager.default.isReadableFile(atPath: url.path), present(url)
        else {
            showFailure()
            return
        }
    }
}
