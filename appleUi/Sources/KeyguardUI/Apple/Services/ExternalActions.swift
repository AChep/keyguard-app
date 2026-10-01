import SwiftUI
import KeyguardShared
import UniformTypeIdentifiers

/// Installs and runs the native URL, sharing, and window actions.
@MainActor
final class ExternalActions {
    private let core: KeyguardCore
    private let links: LinkOpeningCoordinator

    init(core: KeyguardCore, links: LinkOpeningCoordinator) {
        self.core = core
        self.links = links
    }

    private var started = false

    func start() {
        guard !started else { return }
        started = true
        core.setQuickSearchOpenUrlHandler(handler: linkHandler())
        // Producer-emitted `NavigateToBrowser` intents from the navigation interceptor.
        core.setOpenUrlHandler(handler: linkHandler())
        core.setOpenSystemUrlHandler(handler: linkHandler(forceSystem: true))
        // `NavigateToShare` intents carry text, e.g. a Send's public link.
        core.setShareHandler { text in
            ExternalActions.presentShareSheet(text)
        }
        core.setPreviewFileHandler { uri in
            Task { @MainActor in
                let url = uri.hasPrefix("/") ? URL(fileURLWithPath: uri) : URL(string: uri)
                guard let url, url.isFileURL else { return }
                #if os(macOS)
                let panel = NSOpenPanel()
                panel.title = L10n.fileActionOpenWithTitle
                panel.allowedContentTypes = [.application]
                panel.allowsOtherFileTypes = false
                panel.canChooseFiles = true
                panel.directoryURL = URL(fileURLWithPath: "/Applications", isDirectory: true)
                panel.canChooseDirectories = false
                panel.allowsMultipleSelection = false
                panel.treatsFilePackagesAsDirectories = false
                guard panel.runModal() == .OK, let application = panel.url else { return }
                NSWorkspace.shared.open(
                    [url],
                    withApplicationAt: application,
                    configuration: NSWorkspace.OpenConfiguration(),
                    completionHandler: nil
                )
                #else
                ExternalActions.presentShareItems([url])
                #endif
            }
        }
        core.setShareFileHandler { uri in
            Task { @MainActor in
                let url = uri.hasPrefix("/") ? URL(fileURLWithPath: uri) : URL(string: uri)
                guard let url, url.isFileURL else { return }
                ExternalActions.presentShareItems([url])
            }
        }
        core.setRevealFileHandler { uriString in
            Task { @MainActor in
                #if os(macOS)
                guard let url = URL(string: uriString) else { return }
                NSWorkspace.shared.activateFileViewerSelecting([url])
                #else
                // The `shareddocuments:` scheme opens the Files app at a file://
                // path. (No canOpenURL pre-check: querying a custom scheme needs
                // an LSApplicationQueriesSchemes entry; open() fails gracefully.)
                let filesRaw = uriString.replacingOccurrences(of: "file://", with: "shareddocuments://")
                if let filesUrl = URL(string: filesRaw) {
                    UIApplication.shared.open(filesUrl)
                }
                #endif
            }
        }
        // Fires after any clipboard copy while "Minimize after copying" is enabled.
        minimizeOnCopySubscription = BridgeObservation(
            core.observeMinimizeOnCopy {
                Task { @MainActor in
                    ExternalActions.miniaturizeMainWindowIfKey()
                }
            })
    }

    private var minimizeOnCopySubscription: BridgeObservation?

    /// Hops to the main actor, since producer callbacks may fire off it.
    private func linkHandler(forceSystem: Bool = false) -> (String) -> Void {
        { [weak self] urlString in
            Task { @MainActor in self?.links.open(urlString, forceSystem: forceSystem) }
        }
    }

    /// Miniaturizes the main window after a copy, but only while it is the key
    /// window — copies made from the menu-bar popover or the Quick Search panel
    /// (whose own panels are key at that moment) leave the main window alone.
    static func miniaturizeMainWindowIfKey() {
        #if os(macOS)
        guard let window = NSApp.keyWindow,
            window.identifier?.rawValue.hasPrefix("main") == true
        else { return }
        window.miniaturize(nil)
        #endif
    }

    nonisolated static func presentShareSheet(_ text: String) {
        Task { @MainActor in
            presentShareItems([text])
        }
    }

    static func presentShareItems(_ items: [Any]) {
        #if os(macOS)
        let picker = NSSharingServicePicker(items: items)
        guard let view = NSApp.keyWindow?.contentView else { return }
        picker.show(
            relativeTo: .zero,
            of: view,
            preferredEdge: .minY
        )
        #else
        let activity = UIActivityViewController(activityItems: items, applicationActivities: nil)
        guard
            let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }),
            let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
        else { return }
        // Walk to the top-most presented controller so the sheet attaches above
        // any open modal (the Send detail / edit sheet).
        var presenter = root
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        // iPad requires a popover anchor; center it over the presenter's view.
        if let popover = activity.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.midY,
                width: 0,
                height: 0
            )
            popover.permittedArrowDirections = []
        }
        presenter.present(activity, animated: true)
        #endif
    }
}
