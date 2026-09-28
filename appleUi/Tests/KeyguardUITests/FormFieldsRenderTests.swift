#if DEBUG
import SwiftUI
import XCTest
@testable import KeyguardUI

@MainActor
final class FormFieldsRenderTests: XCTestCase {
    func testNativeFormsAtCompactAndRegularWidths() async throws {
        for width in [390, 768] {
            for scenario in ["light", "dark", "large-errors"] {
                let view = FormFieldsPreview(
                    dark: scenario == "dark", largeText: scenario == "large-errors",
                    showError: scenario == "large-errors"
                )
                let size = CGSize(width: width, height: 1100)
                let name = "forms-\(width)-\(scenario)"
                #if os(macOS)
                let host = NSHostingView(rootView: view.frame(width: size.width, height: size.height))
                let window = NSWindow(
                    contentRect: CGRect(origin: .zero, size: size), styleMask: [.titled],
                    backing: .buffered, defer: false
                )
                window.isReleasedWhenClosed = false
                window.contentView = host
                host.layoutSubtreeIfNeeded()
                try await Task.sleep(for: .milliseconds(80))
                host.layoutSubtreeIfNeeded()
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                let attachment = XCTAttachment(image: NSImage(cgImage: try XCTUnwrap(bitmap.cgImage), size: size))
                window.close()
                #else
                let host = UIHostingController(rootView: view)
                host.overrideUserInterfaceStyle = scenario == "dark" ? .dark : .light
                let window = UIWindow(frame: CGRect(origin: .zero, size: size))
                window.rootViewController = host
                window.isHidden = false
                host.view.frame = window.bounds
                host.view.layoutIfNeeded()
                try await Task.sleep(for: .milliseconds(80))
                host.view.layoutIfNeeded()
                let image = UIGraphicsImageRenderer(size: size).image { context in
                    host.view.layer.render(in: context.cgContext)
                }
                let data = try XCTUnwrap(image.pngData())
                let attachment = XCTAttachment(image: image)
                window.isHidden = true
                #endif
                attachment.name = name
                attachment.lifetime = .keepAlways
                add(attachment)
                if let directory = ProcessInfo.processInfo.environment["KEYGUARD_FORM_SNAPSHOTS"] {
                    let url = URL(fileURLWithPath: directory, isDirectory: true)
                    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
                    try data.write(to: url.appendingPathComponent(name).appendingPathExtension("png"))
                }
            }
        }
    }
}
#endif
