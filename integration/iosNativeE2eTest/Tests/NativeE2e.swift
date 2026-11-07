import XCTest

/// A test-only driver. All app interaction goes through XCTest; no app hooks or
/// direct database writes. The host broker binds only to loopback.
final class NativeE2e: XCTestCase {
    private let broker = "http://127.0.0.1:8233"
    private var app = XCUIApplication(bundleIdentifier: "com.artemchep.keyguard.ios.e2e")
    @MainActor private var storeKitFixture: StoreKitFixture?

    @MainActor
    func testInteractiveJourneys() async throws {
        continueAfterFailure = false
        let deadline = Date().addingTimeInterval(14_400)
        while Date() < deadline {
            let (data, _) = try await URLSession.shared.data(from: URL(string: broker + "/next")!)
            guard let command = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                let action = command["action"] as? String
            else { continue }
            var result: [String: Any] = ["id": command["id"] ?? "", "ok": true]
            do {
                try await execute(action, command: command, result: &result)
            } catch {
                result["ok"] = false
                result["error"] = String(describing: error)
            }
            var request = URLRequest(url: URL(string: broker + "/result")!)
            request.httpMethod = "POST"
            request.httpBody = try JSONSerialization.data(withJSONObject: result)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            _ = try await URLSession.shared.data(for: request)
            if action == "stop" { return }
        }
    }

    @MainActor
    private func execute(_ action: String, command: [String: Any], result: inout [String: Any]) async throws {
        switch action {
        case "stop": break
        case "launch": app.launch()
        case "activate":
            if let bundle = command["bundle"] as? String { app = XCUIApplication(bundleIdentifier: bundle) }
            app.activate()
        case "selectApplication":
            guard let bundle = command["bundle"] as? String, !bundle.isEmpty else {
                throw DriverError("Application bundle is required")
            }
            let candidate = XCUIApplication(bundleIdentifier: bundle)
            guard candidate.state != .notRunning else { throw DriverError("Application is not running") }
            app = candidate
            result["applicationState"] = app.state.rawValue
        case "terminate": app.terminate()
        case "home": XCUIDevice.shared.press(.home)
        case "orientation":
            switch command["orientation"] as? String {
            case "portrait": XCUIDevice.shared.orientation = .portrait
            case "portraitUpsideDown": XCUIDevice.shared.orientation = .portraitUpsideDown
            case "landscapeLeft": XCUIDevice.shared.orientation = .landscapeLeft
            case "landscapeRight": XCUIDevice.shared.orientation = .landscapeRight
            default: throw DriverError("Unsupported orientation")
            }
            result["orientation"] = XCUIDevice.shared.orientation.rawValue
        case "deviceInfo":
            result["orientation"] = XCUIDevice.shared.orientation.rawValue
            result["applicationState"] = app.state.rawValue
            if app.state == .runningForeground, let frame = frameSnapshot(app.frame) {
                result["frame"] = frame
            }
        case "accessibilityAudit":
            result["issues"] = try accessibilityAudit()
        case "tree": result["tree"] = app.debugDescription
        case "systemTree":
            result["tree"] = XCUIApplication(bundleIdentifier: "com.apple.springboard").debugDescription
        case "systemTap", "systemType":
            let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let element = try target(command, in: system)
            guard element.isHittable else { throw DriverError("System target is not hittable") }
            element.tap()
            if action == "systemType" {
                try requireKeyboardFocus(in: element)
                element.typeText(command["text"] as? String ?? "")
            }
        case "value":
            if let value = try target(command).value {
                result["value"] = String(describing: value)
            } else {
                result["value"] = NSNull()
            }
        case "screenshot": result["png"] = XCUIScreen.main.screenshot().pngRepresentation.base64EncodedString()
        case "tap", "hold", "type":
            let element = try target(command)
            guard element.isHittable else { throw DriverError("Target is not hittable") }
            if action == "hold" {
                element.press(forDuration: 1.2)
            } else {
                element.tap()
                if action == "type" {
                    try requireKeyboardFocus(in: element)
                    element.typeText(command["text"] as? String ?? "")
                }
            }
        case "typeFocused":
            try requireKeyboardFocus(in: app)
            app.typeText(command["text"] as? String ?? "")
        case "adjustSlider":
            guard let position = command["position"] as? Double,
                position.isFinite, (0...1).contains(position)
            else { throw DriverError("Slider position must be between zero and one") }
            let element = try target(command)
            guard element.elementType == .slider, element.isHittable else {
                throw DriverError("Target must be a hittable slider")
            }
            element.adjust(toNormalizedSliderPosition: position)
        case "doubleTap", "pinch":
            let element = try target(command)
            guard element.isHittable else { throw DriverError("Target is not hittable") }
            if action == "doubleTap" {
                element.doubleTap()
            } else {
                guard let scale = command["scale"] as? Double, scale.isFinite, scale > 0, scale != 1 else {
                    throw DriverError("Pinch requires a positive, non-unit scale")
                }
                let velocity = command["velocity"] as? Double ?? (scale > 1 ? 1 : -1)
                guard velocity.isFinite, velocity != 0, (scale > 1) == (velocity > 0) else {
                    throw DriverError("Pinch velocity must have the same direction as its scale")
                }
                element.pinch(withScale: CGFloat(scale), velocity: CGFloat(velocity))
            }
        case "tapPoint":
            let x = command["x"] as? Double ?? 0
            let y = command["y"] as? Double ?? 0
            let frame = app.frame
            guard x >= 0, y >= 0, x <= frame.width, y <= frame.height else {
                throw DriverError("Point is outside the app")
            }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y)).tap()
        case "drag":
            let start = try coordinate(command, x: "x", y: "y")
            let end = try coordinate(command, x: "toX", y: "toY")
            let duration = command["duration"] as? Double ?? 0.05
            guard duration.isFinite, (0...3).contains(duration) else {
                throw DriverError("Drag duration must be between zero and three seconds")
            }
            let endHold = command["endHold"] as? Double ?? 0
            guard endHold.isFinite, (0...3).contains(endHold) else {
                throw DriverError("Drag end hold must be between zero and three seconds")
            }
            if endHold > 0 {
                start.press(
                    forDuration: duration, thenDragTo: end, withVelocity: .default, thenHoldForDuration: endHold)
            } else {
                start.press(forDuration: duration, thenDragTo: end)
            }
        case "swipe":
            let element = command["label"] == nil ? app : try target(command)
            switch command["direction"] as? String {
            case "down": element.swipeDown()
            case "left": element.swipeLeft()
            case "right": element.swipeRight()
            default: element.swipeUp()
            }
        default:
            guard action.hasPrefix("storekit") else { throw DriverError("Unknown action: \(action)") }
            let fixture = storeKitFixture ?? StoreKitFixture()
            storeKitFixture = fixture
            result.merge(try await fixture.execute(action, command: command)) { _, value in value }
        }
    }

    @MainActor
    private func accessibilityAudit() throws -> [[String: Any]] {
        var issues: [[String: Any]] = []
        try app.performAccessibilityAudit(for: .all) { issue in
            var record: [String: Any] = [
                "auditType": issue.auditType.rawValue,
                "summary": issue.compactDescription,
                "details": issue.detailedDescription,
            ]
            if let element = issue.element, element.exists {
                record["label"] = element.label
                record["elementType"] = element.elementType.rawValue
                if let frame = self.frameSnapshot(element.frame) {
                    record["frame"] = frame
                }
            }
            issues.append(record)
            // The caller records every finding; do not end the interactive test.
            return true
        }
        return issues
    }

    private func frameSnapshot(_ frame: CGRect) -> [String: Double]? {
        let values = [
            "x": Double(frame.origin.x), "y": Double(frame.origin.y),
            "width": Double(frame.width), "height": Double(frame.height),
        ]
        return values.values.allSatisfy(\.isFinite) ? values : nil
    }

    @MainActor
    private func requireKeyboardFocus(in element: XCUIElement) throws {
        // A system password prompt can interrupt a tap. Reject the command before
        // XCTest event synthesis aborts the interactive runner for missing focus.
        // The query chain can describe focused siblings. Only the target's
        // attributes/subtree establish focus for an element.typeText call.
        let subtree = element.debugDescription.components(separatedBy: "Path to element:").first ?? ""
        guard subtree.contains("Keyboard Focused") else {
            throw DriverError("No keyboard focus")
        }
    }

    @MainActor
    private func coordinate(_ command: [String: Any], x: String, y: String) throws -> XCUICoordinate {
        guard let x = command[x] as? Double, let y = command[y] as? Double,
            x >= 0, y >= 0, x <= app.frame.width, y <= app.frame.height
        else { throw DriverError("Point is outside the app") }
        return app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y))
    }

    @MainActor
    private func target(_ command: [String: Any], in application: XCUIApplication? = nil) throws -> XCUIElement {
        let types: [String: XCUIElement.ElementType] = [
            "button": .button, "textField": .textField, "secureTextField": .secureTextField,
            "textView": .textView, "staticText": .staticText, "switch": .switch,
            "cell": .cell, "searchField": .searchField, "tabBar": .tabBar, "key": .key,
            "menuItem": .menuItem, "image": .image, "slider": .slider,
        ]
        let type = types[command["type"] as? String ?? ""] ?? .any
        var query = (application ?? app).descendants(matching: type)
        if let label = command["label"] as? String {
            let predicate = command["contains"] as? Bool == true ? "label CONTAINS %@" : "label == %@"
            query = query.matching(NSPredicate(format: predicate, label))
        }
        if let identifier = command["identifier"] as? String {
            query = query.matching(identifier: identifier)
        }
        if let placeholder = command["placeholder"] as? String {
            query = query.matching(NSPredicate(format: "placeholderValue == %@", placeholder))
        }
        let element = query.element(boundBy: command["index"] as? Int ?? 0)
        guard element.waitForExistence(timeout: 5) else { throw DriverError("Target not found") }
        // SwiftUI keeps the presenting screen in the tree beneath a sheet.
        // Prefer its visible control unless the caller explicitly chose an index.
        if command["index"] == nil,
            let visible = query.allElementsBoundByIndex.first(where: \.isHittable)
        {
            return visible
        }
        return element
    }
}
