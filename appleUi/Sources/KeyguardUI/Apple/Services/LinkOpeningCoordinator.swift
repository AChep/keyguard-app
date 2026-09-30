import Foundation

/// Shares routing between SwiftUI links (iOS) and URLs emitted by the Kotlin producers.
@MainActor
final class LinkOpeningCoordinator {
    private let supportsInAppBrowser: Bool
    private let openSystem: @MainActor (URL, Bool) async -> Bool
    private let showFailure: @MainActor () -> Void
    private var useExternalBrowser: Bool?
    private var pendingURL: URL?
    private var openBrowser: (@MainActor (URL) async -> Bool)?
    private(set) var openingTask: Task<Void, Never>?

    init(
        supportsInAppBrowser: Bool,
        openSystem: @escaping @MainActor (URL, Bool) async -> Bool,
        showFailure: @escaping @MainActor () -> Void
    ) {
        self.supportsInAppBrowser = supportsInAppBrowser
        self.openSystem = openSystem
        self.showFailure = showFailure
    }

    func setBrowserHandler(_ handler: @escaping @MainActor (URL) async -> Bool) {
        openBrowser = handler
    }

    func updatePreference(useExternalBrowser: Bool) {
        self.useExternalBrowser = useExternalBrowser
        if let url = pendingURL { open(url) }
    }

    /// Do not replay a pending tap after the scene has gone into the background.
    func cancelPendingRequests() {
        pendingURL = nil
        openingTask?.cancel()
    }

    func open(_ raw: String, forceSystem: Bool = false) {
        guard let url = URL(string: raw) else {
            showFailure()
            return
        }
        open(url, forceSystem: forceSystem)
    }

    func open(_ url: URL, forceSystem: Bool = false) {
        let scheme = url.scheme?.lowercased()
        let isWeb = scheme == "https" || scheme == "http"
        guard let scheme, !["javascript", "data", "about"].contains(scheme),
            !isWeb || url.host?.isEmpty == false
        else {
            showFailure()
            return
        }
        pendingURL = nil
        // Coalesce taps while an app handoff or presentation is in progress.
        guard openingTask == nil else { return }
        let offersBrowser = supportsInAppBrowser && isWeb && !forceSystem
        if offersBrowser, useExternalBrowser == nil {
            pendingURL = url
            return
        }
        let inApp = offersBrowser && useExternalBrowser == false
        openingTask = Task { [weak self] in
            guard let self else { return }
            defer { openingTask = nil }
            guard !Task.isCancelled else { return }
            // In-app links first preserve installed-app universal links without
            // launching the default browser when no app claims the URL.
            var accepted = await openSystem(url, inApp)
            if !accepted, inApp, !Task.isCancelled { accepted = await openBrowser?(url) ?? false }
            if !accepted, !Task.isCancelled { showFailure() }
        }
    }
}
