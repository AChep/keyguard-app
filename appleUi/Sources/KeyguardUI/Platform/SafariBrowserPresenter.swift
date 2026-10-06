#if os(iOS)
import SafariServices
import UIKit

/// Anchored to the originating window, including while a modal detaches its root view.
@MainActor
final class SafariBrowserPresenter {
    weak var window: UIWindow?

    func open(_ url: URL, systemBrowser: (@MainActor (URL) -> Void)?) async -> Bool {
        guard let presenter = await readyPresenter() else { return false }
        // A second producer event must not stack another browser over this one.
        guard !(presenter is SFSafariViewController) else { return true }
        // The SwiftUI action is captured at the root. It cannot present over a
        // sheet owned by that root, so use the topmost UIKit presenter there.
        if let systemBrowser, presenter === window?.rootViewController {
            systemBrowser(url)
        } else {
            let browser = SFSafariViewController(url: url)
            // Safari owns its modal style, transitions, controls, and dismissal.
            // It must not be embedded as a child of a SwiftUI sheet controller.
            await withCheckedContinuation { continuation in
                presenter.present(browser, animated: true) { continuation.resume() }
            }
        }
        return true
    }

    private func readyPresenter() async -> UIViewController? {
        // A link can be tapped while its SwiftUI sheet is finishing a transition.
        // Wait on that transition rather than racing UIKit's presentation state.
        for _ in 0..<3 {
            guard !Task.isCancelled,
                let window, window.windowScene?.activationState == .foregroundActive,
                var presenter = window.rootViewController
            else { return nil }
            while let presented = presenter.presentedViewController {
                presenter = presented
            }
            if let transition = presenter.transitionCoordinator {
                await Self.waitForTransition { completion in
                    transition.animate(alongsideTransition: nil) { _ in completion() }
                }
                continue
            }
            guard !presenter.isBeingDismissed, !presenter.isBeingPresented,
                presenter.viewIfLoaded?.window != nil
            else { return nil }
            return presenter
        }
        return nil
    }

    static func waitForTransition(
        scheduleCompletion: (@escaping @MainActor () -> Void) -> Bool
    ) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            var pending: CheckedContinuation<Void, Never>? = continuation
            let finish: @MainActor () -> Void = {
                guard let continuation = pending else { return }
                pending = nil
                continuation.resume()
            }
            // UIKit may invoke completion even when animate returns false.
            let scheduled = scheduleCompletion(finish)
            if !scheduled { finish() }
        }
    }
}
#endif
