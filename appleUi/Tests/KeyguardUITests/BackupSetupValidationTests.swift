import XCTest
@testable import KeyguardUI

final class BackupSetupValidationTests: XCTestCase {
    func testAcceptsAbsoluteHTTPAndHTTPSDestinations() {
        for value in [
            "https://example.com",
            "http://localhost:8080/backups/",
            "https://example.com/remote.php/dav/files/vault/",
            "https://example.com/my%20backups?directory=vault",
            "https://192.168.1.2:8443/backups",
            "https://[::1]:8443/backups",
            "HTTPS://EXAMPLE.COM/backups",
            " \nhttps://example.com/backups\t ",
        ] {
            XCTAssertTrue(BackupSetupValidation.isValidWebDAVURL(value), value)
        }
    }

    func testRejectsEmptyRelativeAndUnsupportedDestinations() {
        for value in [
            "",
            " \n\t ",
            "example.com/backups",
            "/backups",
            "//example.com/backups",
            "file:///backups",
            "ftp://example.com/backups",
            "https:",
            "https:///backups",
            "https://",
        ] {
            XCTAssertFalse(BackupSetupValidation.isValidWebDAVURL(value), value)
        }
    }

    func testRejectsMalformedURLsWithoutSilentlyEscapingThem() {
        for value in [
            "https://exa mple.com/backups",
            "https://example.com/my backups",
            "https://example.com/%invalid",
            "https://example.com/%",
            "https://[::1/backups",
            "https://example.com:invalid/backups",
        ] {
            XCTAssertFalse(BackupSetupValidation.isValidWebDAVURL(value), value)
        }
    }

    func testRequiresCredentialsInSeparateFields() {
        for value in [
            "https://user:secret@example.com/backups",
            "https://user@example.com/backups",
            "https://:secret@example.com/backups",
            "https://@example.com/backups",
        ] {
            XCTAssertFalse(BackupSetupValidation.isValidWebDAVURL(value), value)
        }
    }

    func testRejectsFragmentsIncludingAnEmptyFragment() {
        for value in [
            "https://example.com/backups#folder",
            "https://example.com/backups#",
        ] {
            XCTAssertFalse(BackupSetupValidation.isValidWebDAVURL(value), value)
        }
        XCTAssertTrue(BackupSetupValidation.isValidWebDAVURL("https://example.com/backups%23folder"))
    }
}
