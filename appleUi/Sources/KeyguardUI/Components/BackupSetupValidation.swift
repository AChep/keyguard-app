import Foundation

enum BackupSetupValidation {
    static func isValidWebDAVURL(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            let url = URL(string: trimmed, encodingInvalidCharacters: false),
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            let scheme = components.scheme?.lowercased(),
            scheme == "https" || scheme == "http",
            let host = components.host,
            !host.isEmpty,
            components.user == nil,
            components.password == nil,
            components.fragment == nil
        else {
            return false
        }
        return true
    }
}
