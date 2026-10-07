#if os(iOS)
import SwiftUI
import UIKit
import XCTest
@testable import KeyguardUI

@MainActor
final class ListDetailAdaptationTests: XCTestCase {
    func testHistoryBackKeepsSelectedItemInRegularWidth() async throws {
        try await checkHistoryBack(sizeClass: .regular)
    }

    func testHistoryBackKeepsSelectedItemInCompactWidth() async throws {
        try await checkHistoryBack(sizeClass: .compact)
    }

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

    private func checkHistoryBack(sizeClass: UIUserInterfaceSizeClass) async throws {
        let model = BackNavigationModel()
        let host = UIHostingController(
            rootView: BackNavigationContent(model: model)
                .environment(\.horizontalSizeClass, sizeClass == .compact ? .compact : .regular))
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let previousKeyWindow = scene?.keyWindow
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: sizeClass == .compact ? 390 : 1194, height: 834)
        window.rootViewController = host
        host.traitOverrides.horizontalSizeClass = sizeClass
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }
        try await Task.sleep(for: .milliseconds(500))
        let navigation = try XCTUnwrap(navigationController(in: host, title: "History"))
        XCTAssertFalse(
            leadingButtons(in: navigation).contains {
                $0.accessibilityIdentifier == "listDetailBackToList"
            },
            "History must use its own Back action, not the root's clear-selection action")
        XCTAssertNotNil(navigation.popViewController(animated: true))
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(model.ids, [1], "Back must pop history and retain the selected item")
        XCTAssertEqual(model.pops, [1])
        XCTAssertEqual(model.clears, 0, "A column update must not clear the item")
        let itemNavigation = try XCTUnwrap(navigationController(in: host, title: "Item"))
        XCTAssertTrue(itemNavigation.view.window === window, "The item must remain visible after Back")
        if sizeClass == .compact {
            let button = try XCTUnwrap(
                leadingButtons(in: itemNavigation).first {
                    $0.accessibilityIdentifier == "listDetailBackToList"
                })
            XCTAssertTrue(button.isEnabled)
            let control = try XCTUnwrap(controls(in: itemNavigation.navigationBar).last)
            let action = try XCTUnwrap(button.action)
            let target = try XCTUnwrap(button.target as? NSObject)
            _ = target.perform(action, with: control)
            try await Task.sleep(for: .milliseconds(500))
            XCTAssertTrue(model.ids.isEmpty, "The root Back action must return to the list")
            XCTAssertEqual(model.clears, 1)
        }
    }

    private func leadingButtons(in navigation: UINavigationController) -> [UIBarButtonItem] {
        guard let item = navigation.topViewController?.navigationItem else { return [] }
        return (item.leftBarButtonItems ?? []) + item.leadingItemGroups.flatMap(\.barButtonItems)
    }

    private func controls(in view: UIView) -> [UIControl] {
        let own = (view as? UIControl).map { [$0] } ?? []
        return own + view.subviews.flatMap { controls(in: $0) }
    }

    private func navigationController(in controller: UIViewController, title: String) -> UINavigationController? {
        if let navigation = controller as? UINavigationController,
            navigation.topViewController?.navigationItem.title == title
        {
            return navigation
        }
        return controller.children.lazy.compactMap { self.navigationController(in: $0, title: title) }.first
    }

    @Observable
    fileprivate final class BackNavigationModel {
        var ids: [Int64] = [1, 2]
        var pops: [Int64] = []
        var clears = 0

        func popTo(_ id: Int64?) {
            if let id, let index = ids.firstIndex(of: id) {
                ids = Array(ids.prefix(index + 1))
                pops.append(id)
            } else {
                ids = []
            }
        }

        func clear() {
            clears += 1
            ids = []
        }
    }

    private struct BackNavigationContent: View {
        let model: BackNavigationModel

        var body: some View {
            ListDetailNavigationView(
                projection: ListDetailNavigation(root: .vault, entries: model.ids.map { .init(id: $0) }),
                popTo: model.popTo,
                clearDetail: model.clear,
                sidebar: { List { Text("Items") }.navigationTitle("Vault") },
                sidebarDestination: { id in Text("List \(id)") },
                detailDestination: { id in
                    Text(id == 1 ? "Selected item" : "Password history")
                        .navigationTitle(id == 1 ? "Item" : "History")
                }
            )
        }
    }
}
#endif
