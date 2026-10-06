import XCTest
import KeyguardShared
@testable import KeyguardUI

final class SettingsSearchTests: XCTestCase {
    func testRevealWaitsForLoadedDataAndResolvesHiddenControlToPrerequisite() {
        let request = SettingsRevealRequest(target: .biometricTimeout)
        let aliases: [SettingsSearchTarget: SettingsSearchTarget] = [.biometricTimeout: .biometric]
        XCTAssertNil(request.resolved(ready: false, aliases: aliases))
        let resolved = request.resolved(ready: true, aliases: aliases)
        XCTAssertEqual(resolved?.target, .biometric)
        XCTAssertEqual(resolved?.id, request.id)
        XCTAssertEqual(request.resolved(ready: true, aliases: [:])?.target, .biometricTimeout)
    }

    func testRepeatedActivationHasNewIdentityAndKeepsTheSameControl() {
        let first = SettingsRevealRequest(target: .clipboard)
        let second = SettingsRevealRequest(target: .clipboard)
        XCTAssertNotEqual(first.id, second.id)
        XCTAssertEqual(first.target, second.target)
    }

    @MainActor
    func testLicenseTargetsResolveDirectlyToVisiblePrerequisites() {
        for unlocked in [false, true] {
            for store in [false, true] {
                for linked in [false, true] {
                    for claimed in [false, true] {
                        let snapshot = AppleLicenseSnapshot(
                            unlocked: unlocked, claimedKey: claimed ? "masked" : nil, claimedStatus: nil,
                            linkedKey: linked ? "masked" : nil, linkedStatus: nil, busy: false, message: nil)
                        let aliases = AppleLicenseSection.searchAliases(snapshot, storeAvailable: store)
                        var visible: Set<SettingsSearchTarget> = [.licenseEntry]
                        if unlocked {
                            visible.insert(.licenseLink)
                            if store { visible.insert(.licenseSync) }
                            if claimed { visible.insert(.licensePurchaseToken) }
                            if linked { visible.formUnion([.licenseLinkedToken, .licenseRefresh, .licenseRemove]) }
                        }
                        for target: SettingsSearchTarget in [
                            .licenseEntry, .licenseLink, .licenseSync, .licensePurchaseToken,
                            .licenseLinkedToken, .licenseRefresh, .licenseRemove,
                        ] {
                            let resolved = SettingsRevealRequest(target: target).resolved(ready: true, aliases: aliases)
                            XCTAssertTrue(visible.contains(resolved!.target), target.name)
                            if visible.contains(target) { XCTAssertEqual(resolved?.target, target) }
                        }
                    }
                }
            }
        }
    }

    func testEverySearchControlHasExactlyOneNativeAnchor() throws {
        // Guard against adding searchable metadata without implementing its destination.
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/KeyguardUI")
        let files = try XCTUnwrap(
            FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)
        )
        let sources = try files.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
            .map { try String(contentsOf: $0, encoding: .utf8) }
            .joined(separator: "\n")
        for target in SettingsSearchTarget.entries {
            let parts = target.name.lowercased().split(separator: "_")
            let swiftName = String(parts[0]) + parts.dropFirst().map { $0.capitalized }.joined()
            let anchor = ".settingsSearchTarget(.\(swiftName))"
            XCTAssertEqual(sources.components(separatedBy: anchor).count - 1, 1, target.name)
        }
    }
}
