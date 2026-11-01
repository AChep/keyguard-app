import Foundation
import KeyguardShared
import UniformTypeIdentifiers

/// Shared native transport seam. Implementations own prepared bytes, never a producer file URL.
protocol AttachmentDragContent: AnyObject, Sendable {
    var name: String { get }
    func isAvailable() -> Bool
    func handoff()
    func close()
    /// Runs `action` once, on any thread, when the content stops accepting readers; at once
    /// if it already has.
    func onRetirement(_ action: @escaping @Sendable () -> Void)
    /// Complete data for the bounded iOS representation. Blocking: invoke only on a worker.
    func load(cancellation: AttachmentDragReadCancellationSnapshot) -> AttachmentDragLoadResult
    /// Writes a new file at a recipient's destination. Blocking: invoke only on a worker.
    func write(to destination: URL, cancellation: AttachmentDragReadCancellationSnapshot) throws
}

enum AttachmentDragLoadResult: Sendable {
    case data(Data)
    case cancelled
    case failed
}

protocol AttachmentDragPreparation: AnyObject {
    func cancel()
}

protocol AttachmentDragPreparing: AnyObject {
    var name: String { get }
    /// The file name to promise before preparing, when preparation never asks the user anything.
    var promisedFileName: String? { get }
    func prepare(
        completion: @escaping @Sendable ((any AttachmentDragContent)?) -> Void
    ) -> any AttachmentDragPreparation
}

extension UTType {
    /// The type both drag transports offer for a file name; unknown extensions are plain data.
    init(attachmentName name: String) {
        self = UTType(filenameExtension: (name as NSString).pathExtension) ?? .data
    }
}

/// The bridge guards lease transitions; every data operation runs on its caller's worker.
extension AttachmentDragContentSnapshot: @retroactive @unchecked Sendable, AttachmentDragContent {
    func onRetirement(_ action: @escaping @Sendable () -> Void) {
        _ = invokeOnRetirement(action: action)
    }

    func load(cancellation: AttachmentDragReadCancellationSnapshot) -> AttachmentDragLoadResult {
        let result = loadData(cancellation: cancellation)
        if result.cancelled { return .cancelled }
        guard let data = result.data else { return .failed }
        return .data(data as Data)
    }

    /// Throws only redacted Cocoa errors: no vault identifier or private path reaches IPC.
    func write(to destination: URL, cancellation: AttachmentDragReadCancellationSnapshot) throws {
        let path = destination.withUnsafeFileSystemRepresentation { $0.map { String(cString: $0) } }
        guard destination.isFileURL, let path else { throw CocoaError(.fileWriteUnknown) }
        switch writeTo(path: path, cancellation: cancellation) {
        case .written: return
        case .cancelled: throw CocoaError(.userCancelled)
        default: throw CocoaError(.fileWriteUnknown)
        }
    }
}

/// The Kotlin entrypoint dispatches preparation on its authorized screen's main coroutine scope.
extension AttachmentDragSnapshot: AttachmentDragPreparing {
    func prepare(
        completion: @escaping @Sendable ((any AttachmentDragContent)?) -> Void
    ) -> any AttachmentDragPreparation {
        prepare(onResult: { completion($0) })
    }
}

/// Job.cancel() is thread safe and does not perform payload I/O on the calling thread.
extension KeyguardCancellable: AttachmentDragPreparation {}
