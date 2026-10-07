import Foundation
import XCTest
@testable import KeyguardUI

@MainActor
final class FileOpeningCoordinatorTests: XCTestCase {
    func testFilePathsAndEncodedURLsPreserveUnicodeAndSpaces() throws {
        let file = try fixture("Recovery codes ü.txt")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        var opened: [URL] = []
        var failures = 0
        let coordinator = FileOpeningCoordinator(
            present: {
                opened.append($0); return true
            }, showFailure: { failures += 1 })
        coordinator.open(file.path)
        coordinator.open(file.absoluteString)
        XCTAssertEqual(opened, [file, file])
        XCTAssertEqual(failures, 0)
    }

    func testMissingFilesDirectoriesAndNonFileURLsNeverReachPresenter() throws {
        let file = try fixture("attachment.txt")
        let directory = file.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: directory) }
        var calls = 0
        var failures = 0
        let coordinator = FileOpeningCoordinator(
            present: { _ in
                calls += 1; return true
            }, showFailure: { failures += 1 })
        coordinator.open("https://example.com/file.txt")
        coordinator.open("relative-file.txt")
        coordinator.open(directory.path)
        try FileManager.default.removeItem(at: file)
        coordinator.open(file.absoluteString)
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(failures, 4)
    }

    func testUnavailablePresenterReportsFailureAndNextAttemptCanSucceed() throws {
        let file = try fixture("attachment.pdf")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        var available = false
        var calls = 0
        var failures = 0
        let coordinator = FileOpeningCoordinator(
            present: { _ in
                calls += 1; return available
            }, showFailure: { failures += 1 })
        coordinator.open(file.path)
        available = true
        coordinator.open(file.path)
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(failures, 1)
    }

    func testHandoffDoesNotRestrictFileTypesToBuiltInPreviewFormats() throws {
        let file = try fixture("attachment.unknown")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        var opened: URL?
        let coordinator = FileOpeningCoordinator(
            present: {
                opened = $0; return true
            }, showFailure: { XCTFail("Valid download rejected") })
        coordinator.open(file.absoluteString)
        XCTAssertEqual(opened, file)
    }

    func testUnreadableFileReportsFailureWithoutPresenting() throws {
        let file = try fixture("unreadable.txt")
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        }
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: file.path)
        var failures = 0
        let coordinator = FileOpeningCoordinator(
            present: { _ in
                XCTFail("Unreadable file reached presenter"); return true
            },
            showFailure: { failures += 1 })
        coordinator.open(file.path)
        XCTAssertEqual(failures, 1)
    }

    private func fixture(_ name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(name)
        try Data("Synthetic attachment".utf8).write(to: file)
        return file
    }
}
