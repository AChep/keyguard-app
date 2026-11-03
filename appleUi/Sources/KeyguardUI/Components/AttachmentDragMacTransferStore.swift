#if os(macOS)
import AppKit
import KeyguardShared
import UniformTypeIdentifiers
import os

/// AppKit's promise delegate is weak. The application retains bounded offers until they retire,
/// and retains a retired offer only while an admitted reader or native session uses it.
@MainActor
final class AttachmentDragMacTransferStore {
    static let shared = AttachmentDragMacTransferStore()
    private var entries: [UUID: Entry] = [:]

    private struct Entry {
        let offer: AttachmentDragOffer
        let delegate: MacPromiseDelegate
        let provider: NSFilePromiseProvider
        var source: MacSessionSource?
    }

    func start(content: any AttachmentDragContent, from view: NSView, event: NSEvent) {
        let id = UUID()
        let offer = AttachmentDragOffer(content: content, changed: changed(id))
        guard content.isAvailable(), begin(id, offer, from: view, event: event) else {
            content.close()
            return
        }
        offer.handoff()
    }

    /// Starts on the name alone. The offer prepares while the user drags.
    func start(promising preparer: any AttachmentDragPreparing, name: String, from view: NSView, event: NSEvent) {
        let id = UUID()
        let offer = AttachmentDragOffer(promising: name, changed: changed(id))
        guard begin(id, offer, from: view, event: event) else { return }
        offer.prepare(with: preparer)
    }

    private func changed(_ id: UUID) -> @Sendable () -> Void {
        { [weak self] in
            Task { @MainActor in self?.removeIfFinished(id) }
        }
    }

    private func begin(_ id: UUID, _ offer: AttachmentDragOffer, from view: NSView, event: NSEvent) -> Bool {
        guard entries.count < 4, view.window != nil else { return false }
        let delegate = MacPromiseDelegate(offer: offer)
        let type = UTType(attachmentName: offer.name)
        let provider = NSFilePromiseProvider(fileType: type.identifier, delegate: delegate)
        let source = MacSessionSource { [weak self] operation in
            self?.ended(id, operation: operation)
        }
        entries[id] = Entry(offer: offer, delegate: delegate, provider: provider, source: source)
        let item = NSDraggingItem(pasteboardWriter: provider)
        let point = view.convert(event.locationInWindow, from: nil)
        item.setDraggingFrame(
            NSRect(x: point.x - 10, y: point.y - 10, width: 20, height: 20),
            contents: NSWorkspace.shared.icon(for: type)
        )
        view.beginDraggingSession(with: [item], event: event, source: source)
        return true
    }

    private func ended(_ id: UUID, operation: NSDragOperation) {
        entries[id]?.source = nil
        entries[id]?.delegate.sessionEnded(operation: operation)
        removeIfFinished(id)
    }

    private func removeIfFinished(_ id: UUID) {
        guard let entry = entries[id], entry.source == nil, entry.offer.isRetired, !entry.offer.hasReaders
        else { return }
        entries.removeValue(forKey: id)
    }
}

/// Writes only to the destination chosen by the receiving application. The offer retires once
/// the session accepted the drop and its promised file was written, in either order; later
/// requests fail. Kotlin's deadline retires it if an accepting destination never asks.
@MainActor
final class MacPromiseDelegate: NSObject, NSFilePromiseProviderDelegate {
    // Shared across offers: callbacks cannot create an unbounded set of I/O workers.
    nonisolated private static let workers: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "Attachment file promises"
        queue.maxConcurrentOperationCount = 2
        queue.qualityOfService = .userInitiated
        return queue
    }()
    nonisolated private let offer: AttachmentDragOffer
    nonisolated private let delivery = OSAllocatedUnfairLock(initialState: Delivery())

    /// This provider promises one file. Failed or cancelled writes do not deliver it.
    private struct Delivery {
        var accepted = false
        var written = false
    }

    init(offer: AttachmentDragOffer) {
        self.offer = offer
    }

    /// A rejected drop retires the offer at once; an accepted one retires once delivered.
    nonisolated func sessionEnded(operation: NSDragOperation) {
        if operation.isEmpty {
            offer.retire()
        } else {
            update { $0.accepted = true }
        }
    }

    nonisolated private func update(_ change: @Sendable (inout Delivery) -> Void) {
        let delivered = delivery.withLock {
            change(&$0)
            return $0.accepted && $0.written
        }
        if delivered { offer.retire() }
    }

    // AppKit invokes these callbacks on its operation queue, including metadata.
    nonisolated func filePromiseProvider(
        _ filePromiseProvider: NSFilePromiseProvider,
        fileNameForType fileType: String
    ) -> String { offer.name }

    nonisolated func operationQueue(for filePromiseProvider: NSFilePromiseProvider) -> OperationQueue {
        Self.workers
    }

    nonisolated func filePromiseProvider(
        _ filePromiseProvider: NSFilePromiseProvider,
        writePromiseTo url: URL,
        completionHandler: @escaping ((any Error)?) -> Void
    ) {
        // A promised offer may still be preparing: this worker waits for it.
        guard let content = offer.admit() else {
            completionHandler(CocoaError(.fileReadUnknown))
            return
        }
        defer { offer.readerFinished() }
        do {
            try content.write(to: url, cancellation: AttachmentDragReadCancellationSnapshot())
            // Before replying: a request that follows the delivery must not be admitted.
            update { $0.written = true }
            completionHandler(nil)
        } catch let error as CocoaError where error.code == .userCancelled {
            completionHandler(CocoaError(.userCancelled))
        } catch {
            // Do not put vault identifiers, private paths, or bridge errors in IPC errors.
            completionHandler(CocoaError(.fileWriteUnknown))
        }
    }
}

/// Kept until AppKit ends the session, without retaining its source view or screen.
@MainActor
private final class MacSessionSource: NSObject, NSDraggingSource {
    private let ended: (NSDragOperation) -> Void

    init(ended: @escaping (NSDragOperation) -> Void) {
        self.ended = ended
    }

    func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext)
        -> NSDragOperation
    { .copy }

    func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        ended(operation)
    }
}
#endif
