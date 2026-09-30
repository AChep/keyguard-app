#if os(iOS)
import SwiftUI

struct LinkOpeningModifier: ViewModifier {
    let links: LinkOpeningCoordinator
    @Environment(\.openURL) private var systemOpenURL
    @State private var presenter = SafariBrowserPresenter()

    func body(content: Content) -> some View {
        content
            .background(
                BrowserPresentationAnchor(presenter: presenter).allowsHitTesting(false).accessibilityHidden(true)
            )
            .environment(
                \.openURL,
                OpenURLAction { url in
                    links.open(url)
                    return .handled
                }
            )
            .onAppear {
                // Capture the system action above our environment override to
                // prevent SwiftUI Link / Markdown URLs from recursing into it.
                let systemOpenURL = systemOpenURL
                let presenter = presenter
                links.setBrowserHandler { url in
                    if #available(iOS 26.0, *) {
                        return await presenter.open(url) { systemOpenURL($0, prefersInApp: true) }
                    } else {
                        return await presenter.open(url, systemBrowser: nil)
                    }
                }
            }
    }
}
#endif
