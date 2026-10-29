#if os(macOS) && DEBUG
import SwiftUI
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class AttachmentPreviewJsonRowTests: XCTestCase {
    func testLongRowsStayWithinNarrowWidthAndGrowForAccessibleText() {
        let row = AttachmentPreviewJsonRowsFixtures.longRow
        let regular = NSHostingView(
            rootView: AttachmentPreviewJsonRow(row: row, expanded: false, toggle: {})
                .dynamicTypeSize(.large)
                .frame(width: 220)
        )
        let accessible = NSHostingView(
            rootView: AttachmentPreviewJsonRow(row: row, expanded: false, toggle: {})
                .dynamicTypeSize(.accessibility3)
                .frame(width: 220)
        )
        XCTAssertEqual(regular.fittingSize.width, 220, accuracy: 0.5)
        XCTAssertEqual(accessible.fittingSize.width, 220, accuracy: 0.5)
        XCTAssertGreaterThan(accessible.fittingSize.height, regular.fittingSize.height)
    }

    func testContainerIsAnAccessibleButtonWithExpansionState() async throws {
        let row = AttachmentPreviewJsonRowsFixtures.row("container", key: "account", type: .object, count: 2)
        var presses = 0
        let host = NSHostingView(rootView: AttachmentPreviewJsonRow(row: row, expanded: false) { presses += 1 })
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 320, height: 160),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = host
        defer { window.close() }
        await settle(host)

        let collapsed = try XCTUnwrap(accessibleElements(host).first { $0.accessibilityRole() == .button })
        XCTAssertTrue(collapsed.accessibilityLabel()?.contains("account") == true)
        XCTAssertTrue(collapsed.accessibilityLabel()?.contains(L10n.attachmentPreviewJsonObject) == true)
        XCTAssertEqual(collapsed.accessibilityValue() as? String, L10n.attachmentPreviewJsonCollapsed)
        XCTAssertTrue(collapsed.accessibilityPerformPress())
        await settle(host)
        XCTAssertEqual(presses, 1)

        host.rootView = AttachmentPreviewJsonRow(row: row, expanded: true) { presses += 1 }
        await settle(host)
        let expanded = try XCTUnwrap(accessibleElements(host).first { $0.accessibilityRole() == .button })
        XCTAssertEqual(expanded.accessibilityValue() as? String, L10n.attachmentPreviewJsonExpanded)
    }

    func testRowsRenderAtNarrowWidths() async throws {
        for accessible in [false, true] {
            let size = CGSize(width: accessible ? 390 : 320, height: 900)
            let host = NSHostingView(
                rootView: AttachmentPreviewJsonRowsPreview()
                    .dynamicTypeSize(accessible ? .accessibility3 : .large)
                    .frame(width: size.width, height: size.height)
            )
            let window = NSWindow(
                contentRect: CGRect(origin: .zero, size: size),
                styleMask: [.titled], backing: .buffered, defer: false
            )
            window.isReleasedWhenClosed = false
            window.contentView = host
            await settle(host)
            let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
            host.cacheDisplay(in: host.bounds, to: bitmap)
            let image = NSImage(cgImage: try XCTUnwrap(bitmap.cgImage), size: size)
            let attachment = XCTAttachment(image: image)
            let name = accessible ? "json-rows-accessible" : "json-rows-narrow"
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            if let directory = ProcessInfo.processInfo.environment["KEYGUARD_JSON_SNAPSHOTS"] {
                let url = URL(fileURLWithPath: directory, isDirectory: true)
                try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                    .write(to: url.appendingPathComponent(name).appendingPathExtension("png"))
            }
            window.close()
        }
    }

    private func settle(_ view: NSView) async {
        view.layoutSubtreeIfNeeded()
        try? await Task.sleep(for: .milliseconds(40))
        view.layoutSubtreeIfNeeded()
    }

    private func accessibleElements(_ object: Any) -> [any NSAccessibilityProtocol] {
        guard let element = object as? any NSAccessibilityProtocol else { return [] }
        return [element] + (element.accessibilityChildren() ?? []).flatMap(accessibleElements)
    }
}
#endif
