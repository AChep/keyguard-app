#if os(iOS)
import SwiftUI
import UIKit

/// Connects the app root to its own scene, including when another iPad scene is active.
struct PrivacyScreen: UIViewRepresentable {
    func makeUIView(context: Context) -> SceneObserverView { SceneObserverView() }

    func updateUIView(_ uiView: SceneObserverView, context: Context) {}

    static func dismantleUIView(_ uiView: SceneObserverView, coordinator: ()) {
        uiView.privacyCover.stop()
    }

    final class SceneObserverView: UIView {
        let privacyCover: ScenePrivacyCover

        init(privacyCover: ScenePrivacyCover = ScenePrivacyCover()) {
            self.privacyCover = privacyCover
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            // A full-screen presentation can temporarily detach the presenting
            // view. Keep protecting its scene until SwiftUI dismantles this root.
            if let scene = window?.windowScene {
                privacyCover.attach(to: scene)
            }
        }
    }
}
#endif
