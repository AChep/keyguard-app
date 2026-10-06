#if os(iOS)
import SwiftUI
import UIKit
import XCTest
@testable import KeyguardUI

@MainActor
final class ListDetailAdaptationTests: XCTestCase {
    func testResizingDoesNotPopSelectionOrDetailHistory() async throws {
        let projection = ListDetailNavigation(
            root: .vault, entries: [.init(id: 1), .init(id: 2)])
        try await checkAdaptation(projection)
    }

    func testNestedListSurvivesCompactAndRegularTransitions() async throws {
        let projection = ListDetailNavigation(
            root: nil, entries: [.init(id: 1), .init(id: 2, isVaultList: true), .init(id: 3), .init(id: 4)])
        try await checkAdaptation(projection)
    }

    func testUnselectedSendKeepsItsPromptAcrossSizes() async throws {
        try await checkAdaptation(ListDetailNavigation(root: .send, entries: []))
    }

    private func checkAdaptation(_ projection: ListDetailNavigation) async throws {
        var mutations: [String] = []
        let view = ListDetailNavigationView(
            projection: projection,
            popTo: { mutations.append("pop \(String(describing: $0))") },
            clearDetail: { mutations.append("clear") },
            sidebar: { List { Text("Items") }.navigationTitle("List") },
            sidebarDestination: { id in Text("List \(id)").navigationTitle("List") },
            detailDestination: { id in Text("Detail \(id)").navigationTitle("Detail") }
        )
        let host = UIHostingController(rootView: view)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let previousKeyWindow = scene?.keyWindow
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 1194, height: 834)
        window.rootViewController = host
        host.traitOverrides.horizontalSizeClass = .regular
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        let sizes: [(Double, Double, UIUserInterfaceSizeClass)] = [
            (1194, 834, .regular), (390, 834, .compact), (834, 1194, .regular),
        ]
        for (width, height, sizeClass) in sizes {
            window.frame.size = CGSize(width: width, height: height)
            host.traitOverrides.horizontalSizeClass = sizeClass
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(400))
            let split = try XCTUnwrap(splitController(in: host))
            XCTAssertEqual(split.isCollapsed, sizeClass == .compact)
            XCTAssertTrue(mutations.isEmpty, "Resizing must not change navigation: \(mutations)")
        }
        host.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge
        host.view.semanticContentAttribute = .forceRightToLeft
        host.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(400))
        XCTAssertTrue(mutations.isEmpty)
    }

    private func splitController(in controller: UIViewController) -> UISplitViewController? {
        if let split = controller as? UISplitViewController { return split }
        return controller.children.lazy.compactMap { self.splitController(in: $0) }.first
    }
}
#endif
