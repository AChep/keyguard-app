import SwiftUI

public struct AppStartupView<Content: View>: View {
    private let startup: AppStartup
    private let content: (AppViewModel) -> Content

    public init(startup: AppStartup, @ViewBuilder content: @escaping (AppViewModel) -> Content) {
        self.startup = startup
        self.content = content
    }

    public var body: some View {
        Group {
            if let errorKey = startup.errorKey {
                ContentUnavailableView {
                    Label(
                        String(
                            localized: String.LocalizationValue(startup.errorTitleKey),
                            bundle: AppLocalization.shared.bundle),
                        systemImage: "exclamationmark.triangle")
                } description: {
                    Text(String(localized: String.LocalizationValue(errorKey), bundle: AppLocalization.shared.bundle))
                    if let details = startup.errorDetails {
                        Text(details)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                    }
                } actions: {
                    Button(L10n.retry) { startup.start() }
                    #if os(macOS)
                    Button(L10n.quit) { NSApplication.shared.terminate(nil) }
                    #endif
                }
            } else if let model = startup.model {
                content(model)
            } else {
                ProgressView()
            }
        }
        .task { startup.start() }
    }
}
