import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class KeyguardDevicesModelTests: XCTestCase {
    @MainActor
    func testScreenKeepsOneSessionAndRoutesActions() async throws {
        var sources: [KeyguardDevicesSourceProbe] = []
        let model = KeyguardDevicesModel {
            let source = KeyguardDevicesSourceProbe()
            sources.append(source)
            return source
        }
        model.start()
        model.start()
        XCTAssertEqual(sources.count, 1)
        let source = try XCTUnwrap(sources.first)
        source.publish(devices(["phone", "mac"]))
        try await settle()
        XCTAssertEqual(source.starts, 1)
        XCTAssertTrue(model.snapshot.loaded)
        XCTAssertEqual(model.snapshot.devices.map(\.id), ["phone", "mac"])
        XCTAssertEqual(model.snapshot.addDeviceTitle, "Link another device")

        model.remove(id: "phone")
        model.leave()
        XCTAssertEqual(source.actions, ["remove:phone", "leave"])

        // Pushing the add-device screen hides this one; returning observes again.
        model.stop()
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertFalse(model.snapshot.loaded)
        model.start()
        XCTAssertEqual(sources.count, 2)
        model.stop()
    }

    @MainActor
    func testStoppedScreenDropsLateSnapshots() async throws {
        let source = KeyguardDevicesSourceProbe()
        let model = KeyguardDevicesModel { source }
        model.start()
        model.stop()
        source.publish(devices(["late"]))
        try await settle()
        XCTAssertFalse(model.snapshot.loaded)
        XCTAssertTrue(model.snapshot.devices.isEmpty)
        // Actions of a stopped screen reach nothing.
        model.leave()
        XCTAssertTrue(source.actions.isEmpty)
    }

    private func devices(_ ids: [String]) -> KeyguardDevicesSnapshot {
        let empty = KeyguardDevicesSnapshot.companion.empty
        let devices = ids.map { id in
            KeyguardDevicesSnapshot.Device(
                id: id, label: id, platform: "macos", fingerprint: "0000 0000 0000 0000", isThisDevice: id == ids.first,
                addedText: "Added", status: nil, statusText: nil, lastSeenAtMs: nil, lastTransportText: nil,
                canRemove: true)
        }
        return KeyguardDevicesSnapshot(
            loaded: true, header: empty.header, removed: false, removedByRemoved: false, devices: devices,
            canAddDevice: true, addDeviceTitle: "Link another device",
            canLeave: true, leaveTitle: "Unlink", leaveText: "")
    }

    @MainActor
    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
final class KeyguardDevicesSourceProbe: KeyguardDevicesSource {
    var publish: (KeyguardDevicesSnapshot) -> Void = { _ in }
    var starts = 0
    var closes = 0
    var cancellations = 0
    var actions: [String] = []

    func subscribe(onChange: @escaping (KeyguardDevicesSnapshot) -> Void) -> BridgeObservation {
        starts += 1
        publish = onChange
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }

    func close() { closes += 1 }
    func remove(id: String) { actions.append("remove:\(id)") }
    func leave() { actions.append("leave") }
}
