import AuthenticationServices
import SwiftUI

/// Owns only the browser session for one login presentation.
@MainActor
final class SsoBrowserSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    weak var window: ASPresentationAnchor?
    private var session: ASWebAuthenticationSession?
    private var requestId: String?

    func start(id: String, url: String, completion: @escaping @MainActor (String?, Bool) -> Void) {
        guard requestId != id else { return }
        cancel()
        guard let url = URL(string: url), window != nil else {
            completion(nil, true)
            return
        }
        requestId = id
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "bitwarden") { [weak self] url, error in
            Task { @MainActor in
                guard let self, self.requestId == id else { return }
                self.session = nil
                self.requestId = nil
                let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                completion(url?.absoluteString, error != nil && !cancelled)
            }
        }
        session.presentationContextProvider = self
        self.session = session
        if !session.start() {
            cancel()
            completion(nil, true)
        }
    }

    func cancel() {
        requestId = nil
        session?.cancel()
        session = nil
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        window ?? ASPresentationAnchor()
    }
}

#if os(iOS)
struct SsoBrowserAnchor: UIViewRepresentable {
    let session: SsoBrowserSession
    func makeUIView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.session = session
        return view
    }
    func updateUIView(_ uiView: AnchorView, context: Context) {}
    final class AnchorView: UIView {
        weak var session: SsoBrowserSession?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            session?.window = window
        }
    }
}
#else
struct SsoBrowserAnchor: NSViewRepresentable {
    let session: SsoBrowserSession
    func makeNSView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.session = session
        return view
    }
    func updateNSView(_ nsView: AnchorView, context: Context) {}
    final class AnchorView: NSView {
        weak var session: SsoBrowserSession?
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            session?.window = window
        }
    }
}
#endif
