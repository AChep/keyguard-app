import SwiftUI
import KeyguardShared

/// Keeps the scroll request pending until the screen snapshot is ready. Aliases
/// point hidden options at their prerequisite without changing any preference.
struct SettingsForm<Content: View>: View {
    @Environment(\.settingsRevealRequest) private var request
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var highlighted: SettingsRevealRequest?

    let ready: Bool
    let aliases: [SettingsSearchTarget: SettingsSearchTarget]
    // Built once by the caller, so highlight changes don't re-run its row builder.
    let content: Content

    init(
        ready: Bool = true,
        aliases: [SettingsSearchTarget: SettingsSearchTarget] = [:],
        @ViewBuilder content: () -> Content
    ) {
        self.ready = ready
        self.aliases = aliases
        self.content = content()
    }

    private var resolved: SettingsRevealRequest? { request?.resolved(ready: ready, aliases: aliases) }

    var body: some View {
        ScrollViewReader { proxy in
            Form { content }
                .formStyle(.grouped)
                .environment(\.settingsRevealRequest, highlighted)
                .task(id: resolved) {
                    highlighted = nil
                    guard let resolved else { return }
                    // Let the Form lay out newly loaded rows before asking it to scroll.
                    await Task.yield()
                    guard !Task.isCancelled else { return }
                    withAnimation(reduceMotion ? nil : .default) {
                        proxy.scrollTo(resolved.target.name, anchor: .center)
                    }
                    highlighted = resolved
                    do {
                        try await Task.sleep(for: .seconds(2))
                        highlighted = nil
                    } catch {
                        // A newer activation owns the highlight now.
                    }
                }
        }
    }
}
