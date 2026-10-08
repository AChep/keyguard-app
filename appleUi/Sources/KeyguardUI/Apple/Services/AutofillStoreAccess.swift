import Foundation
import Darwin
import KeyguardShared

/// Shared by the app and appex. Locks are released by the OS if a process exits.
/// The app prepares snapshots outside the lock. Extension mutations and their
/// identity updates share the lock; neither process waits for user input while holding it.
@MainActor
final class AutofillStoreAccess {
    enum Failure: Error { case sharedStorageUnavailable, busy }

    static var sharedDirectory: URL? {
        guard let path = KeyguardStorage.shared.sharedContainerPath() else { return nil }
        return URL(filePath: path, directoryHint: .isDirectory)
            .appending(path: "Keyguard", directoryHint: .isDirectory)
    }

    private let resolveDirectory: @MainActor () -> URL?

    init(resolveDirectory: @escaping @MainActor () -> URL? = { AutofillStoreAccess.sharedDirectory }) {
        self.resolveDirectory = resolveDirectory
    }

    convenience init(directory: URL?) {
        self.init(resolveDirectory: { directory })
    }

    func revision() throws -> String {
        guard let directory = resolveDirectory() else { throw Failure.sharedStorageUnavailable }
        let url = directory.appending(path: "autofill-index-revision")
        do { return try String(contentsOf: url, encoding: .utf8) } catch let error as CocoaError
            where error.code == .fileReadNoSuchFile
        { return "" }
    }

    func markChanged() throws {
        guard let directory = resolveDirectory() else { throw Failure.sharedStorageUnavailable }
        try UUID().uuidString.write(
            to: directory.appending(path: "autofill-index-revision"), atomically: true, encoding: .utf8)
    }

    func withLock<T>(_ operation: @MainActor () async throws -> T) async throws -> T {
        guard let directory = resolveDirectory() else { throw Failure.sharedStorageUnavailable }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let path = directory.appending(path: "autofill-index.lock").path
        let descriptor = open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer { close(descriptor) }
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        while flock(descriptor, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EAGAIN else {
                throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
            }
            guard ContinuousClock.now < deadline else { throw Failure.busy }
            try await Task.sleep(for: .milliseconds(25))
        }
        defer { flock(descriptor, LOCK_UN) }
        try Task.checkCancellation()
        return try await operation()
    }
}
