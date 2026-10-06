import SwiftUI

#if os(macOS)
import AppKit

public struct VisualEffectBackground: NSViewRepresentable {
    public init() {}

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .underWindowBackground
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
#else
import UIKit

public struct VisualEffectBackground: UIViewRepresentable {
    public init() {}

    public func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial))
    }

    public func updateUIView(_ uiView: UIVisualEffectView, context: Context) {}
}
#endif
