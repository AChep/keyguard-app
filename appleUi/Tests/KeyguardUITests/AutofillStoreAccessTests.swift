import Foundation
import XCTest
@testable import KeyguardUI

@MainActor
final class AutofillStoreAccessTests: XCTestCase {
    func testIndependentWritersSerializeAndDetectChangedRevision() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let app = AutofillStoreAccess(directory: directory)
        let appex = AutofillStoreAccess(directory: directory)
        let snapshotRevision = try await app.withLock { try app.revision() }
        var release: CheckedContinuation<Void, Never>?
        let extensionWrite = Task {
            try await appex.withLock {
                try appex.markChanged()
                await withCheckedContinuation { release = $0 }
            }
        }
        while release == nil { await Task.yield() }
        var replaced = false
        let appWrite = Task {
            try await app.withLock {
                guard try app.revision() == snapshotRevision else { return }
                replaced = true
            }
        }
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertFalse(replaced)
        release?.resume()
        try await extensionWrite.value
        try await appWrite.value
        XCTAssertFalse(replaced, "An older full rebuild must not replace an extension-created identity")
        XCTAssertNotEqual(try app.revision(), snapshotRevision)
    }

    #if os(macOS)
    func testTerminatedProcessReleasesSharedLock() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let child = Process()
        child.executableURL = URL(filePath: "/usr/bin/python3")
        child.arguments = [
            "-c",
            """
            import fcntl, pathlib, signal, sys
            directory = pathlib.Path(sys.argv[1])
            with (directory / 'autofill-index.lock').open('w') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                (directory / 'autofill-index-revision').write_text('child-committed')
                (directory / 'ready').touch()
                signal.pause()
            """, directory.path,
        ]
        try child.run()
        defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while !FileManager.default.fileExists(atPath: directory.appending(path: "ready").path),
            ContinuousClock.now < deadline
        {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appending(path: "ready").path))
        let app = AutofillStoreAccess(directory: directory)
        var acquired = false
        let waiting = Task {
            try await app.withLock {
                acquired = true; return try app.revision()
            }
        }
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertFalse(acquired)
        child.terminate()
        let revision = try await waiting.value
        XCTAssertTrue(acquired)
        XCTAssertEqual(revision, "child-committed")
    }
    #endif

    func testThrownOperationReleasesLockForNextWriter() async throws {
        enum Failure: Error { case expected }
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = AutofillStoreAccess(directory: directory)
        let second = AutofillStoreAccess(directory: directory)
        do { try await first.withLock { throw Failure.expected }; XCTFail() } catch Failure.expected {}
        try await second.withLock { try second.markChanged() }
        XCTAssertFalse(try second.revision().isEmpty)
    }

    func testMissingSharedContainerFailsInsteadOfUsingPrivateStorage() async throws {
        let sut = AutofillStoreAccess(directory: nil)
        do { try await sut.withLock { XCTFail("Must not open a fallback vault") }; XCTFail() } catch AutofillStoreAccess
            .Failure.sharedStorageUnavailable
        {}
    }

    func testUnavailableContainerIsResolvedAgainAfterRecovery() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var availableDirectory: URL?
        let sut = AutofillStoreAccess(resolveDirectory: { availableDirectory })
        do { try await sut.withLock { XCTFail("Storage is not available yet") }; XCTFail() } catch AutofillStoreAccess
            .Failure.sharedStorageUnavailable
        {}

        availableDirectory = directory
        try await sut.withLock { try sut.markChanged() }
        XCTAssertFalse(try sut.revision().isEmpty)
    }
}
