#if os(macOS)
import Foundation
import XCTest
@testable import KeyguardUI

@MainActor
final class NativeWindowActivationTests: XCTestCase {
    func testRealNotificationTransportOnlyReachesMatchingGroup() async {
        let group = "keyguard.startup.test.\(UUID())"
        let opened = expectation(description: "Same group receives the request")
        let wrongGroup = expectation(description: "Other group remains quiet")
        wrongGroup.isInverted = true
        let owner = NativeWindowActivation(appGroup: group) { opened.fulfill() }
        let other = NativeWindowActivation(appGroup: group + ".other") { wrongGroup.fulfill() }
        let sender = NativeWindowActivation(appGroup: group) {}
        sender.activateExisting(yieldingTo: nil)
        await fulfillment(of: [opened], timeout: 3)
        await fulfillment(of: [wrongGroup], timeout: 0.2)
        withExtendedLifetime((owner, other, sender)) {}
    }

    func testSenderControlledPayloadAndObjectAreIgnored() async {
        let group = "keyguard.startup.test.\(UUID())"
        let opened = expectation(description: "Payload must not request an action")
        opened.isInverted = true
        let owner = NativeWindowActivation(appGroup: group) { opened.fulfill() }
        let name = Notification.Name("\(group).keyguard.open-main-window")
        DistributedNotificationCenter.default().postNotificationName(
            name, object: "untrusted", userInfo: nil, deliverImmediately: true)
        DistributedNotificationCenter.default().postNotificationName(
            name, object: nil, userInfo: ["action": "unlock"], deliverImmediately: true)
        await fulfillment(of: [opened], timeout: 0.2)
        withExtendedLifetime(owner) {}
    }
}
#endif
