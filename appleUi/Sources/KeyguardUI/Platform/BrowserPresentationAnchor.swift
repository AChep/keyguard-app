#if os(iOS)
import SwiftUI

struct BrowserPresentationAnchor: UIViewRepresentable {
    let presenter: SafariBrowserPresenter

    func makeUIView(context: Context) -> AnchorView { AnchorView(presenter: presenter) }
    func updateUIView(_ uiView: AnchorView, context: Context) {}

    static func dismantleUIView(_ uiView: AnchorView, coordinator: ()) {
        uiView.presenter.window = nil
    }

    final class AnchorView: UIView {
        let presenter: SafariBrowserPresenter

        init(presenter: SafariBrowserPresenter) {
            self.presenter = presenter
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { presenter.window = window }
        }
    }
}
#endif
