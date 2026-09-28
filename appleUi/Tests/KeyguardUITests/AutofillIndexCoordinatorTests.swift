import Foundation
import XCTest
@testable import KeyguardUI

@MainActor
final class AutofillIndexCoordinatorTests: XCTestCase {
    private enum Failure: Error { case read, write }

    private func eventually(_ predicate: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !predicate(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(1))
        }
        XCTAssertTrue(predicate(), "Timed out waiting for coordinator")
    }

    func testReadFailurePreservesWorkingIndexAndCanRetry() async throws {
        var stored = [1]
        var fails = true
        var reported = 0
        let sut = AutofillIndexCoordinator<[Int]>(
            enabled: { true },
            load: {
                if fails { throw Failure.read }; return [2]
            },
            replace: { value, _ in
                stored = value; return true
            },
            reportFailure: { _ in reported += 1 })
        sut.refresh()
        try await eventually { sut.state == .failed }
        XCTAssertEqual(stored, [1])
        XCTAssertEqual(reported, 1)
        fails = false
        sut.refresh()
        try await eventually { sut.state == .current }
        XCTAssertEqual(stored, [2])
    }

    func testDisabledAndLockedDoNotReadVaultButEmptyVaultClearsIndex() async throws {
        var enabled = false
        var reads = 0
        var stored = [1]
        let sut = AutofillIndexCoordinator<[Int]>(
            enabled: { enabled },
            load: {
                reads += 1; return []
            },
            replace: { value, _ in
                stored = value; return true
            }, reportFailure: { _ in XCTFail() })
        sut.refresh()
        try await eventually { sut.state == .disabled }
        XCTAssertEqual(reads, 0)
        enabled = true
        sut.refresh(available: false)
        try await eventually { sut.state == .locked }
        XCTAssertEqual(reads, 0)
        XCTAssertEqual(stored, [1])
        sut.refresh()
        try await eventually { sut.state == .current }
        XCTAssertEqual(stored, [])
    }

    func testNewInvalidationDiscardsSuspendedSnapshot() async throws {
        var pending: CheckedContinuation<Int?, Never>?
        var reads = 0
        var writes: [Int] = []
        let sut = AutofillIndexCoordinator<Int>(
            enabled: { true },
            load: {
                reads += 1
                if reads == 1 { return await withCheckedContinuation { pending = $0 } }
                return 2
            },
            replace: { value, _ in
                writes.append(value); return true
            }, reportFailure: { _ in XCTFail() })
        sut.refresh()
        try await eventually { pending != nil }
        sut.refresh()
        pending?.resume(returning: 1)
        pending = nil
        try await eventually { sut.state == .current }
        XCTAssertEqual(writes, [2])
    }

    func testWritesAreSerializedAndNewestInvalidationIsNotLost() async throws {
        var pending: CheckedContinuation<Void, Never>?
        var reads = 0
        var writes: [Int] = []
        var writing = false
        let sut = AutofillIndexCoordinator<Int>(
            enabled: { true },
            load: {
                reads += 1; return reads
            },
            replace: { value, isCurrent in
                XCTAssertFalse(writing)
                writing = true
                if value == 1 { await withCheckedContinuation { pending = $0 } }
                defer { writing = false }
                guard isCurrent() else { return false }
                writes.append(value)
                return true
            }, reportFailure: { _ in XCTFail() })
        sut.refresh()
        try await eventually { pending != nil }
        for _ in 0..<5 { sut.refresh() }
        pending?.resume()
        pending = nil
        try await eventually { sut.state == .current }
        XCTAssertEqual(reads, 2)
        XCTAssertEqual(writes, [2])
    }

    func testBackgroundingRejectsSuspendedReadAndForegroundReconciles() async throws {
        var pending: CheckedContinuation<Int?, Never>?
        var reads = 0
        var writes: [Int] = []
        let sut = AutofillIndexCoordinator<Int>(
            enabled: { true },
            load: {
                reads += 1
                if reads == 1 { return await withCheckedContinuation { pending = $0 } }
                return 2
            },
            replace: { value, _ in
                writes.append(value); return true
            }, reportFailure: { _ in XCTFail() })
        sut.refresh()
        try await eventually { pending != nil }
        sut.setForeground(false)
        pending?.resume(returning: 1)
        pending = nil
        for _ in 0..<10 { await Task.yield() }
        XCTAssertTrue(writes.isEmpty)
        sut.setForeground(true)
        try await eventually { sut.state == .current }
        XCTAssertEqual(writes, [2])
    }

    func testExternalRevisionMismatchReloadsWithoutReportingSuccess() async throws {
        var reads = 0
        var attempts = 0
        let sut = AutofillIndexCoordinator<Int>(
            enabled: { true },
            load: {
                reads += 1; return reads
            },
            replace: { value, _ in
                attempts += 1; return value > 1
            }, reportFailure: { _ in XCTFail() })
        sut.refresh()
        try await eventually { sut.state == .current }
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(reads, 2)
    }

    func testStoreFailureIsVisibleAndRetryable() async throws {
        var fails = true
        let sut = AutofillIndexCoordinator<Int>(
            enabled: { true }, load: { 1 },
            replace: { _, _ in
                if fails { throw Failure.write }; return true
            }, reportFailure: { _ in })
        sut.refresh()
        try await eventually { sut.state == .failed }
        fails = false
        sut.refresh()
        try await eventually { sut.state == .current }
    }
}
