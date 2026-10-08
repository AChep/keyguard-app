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
            if let model = startup.model {
                content(model)
            } else if let errorKey = startup.errorKey {
                ContentUnavailableView {
                    Label(L10n.storageErrorTitle, systemImage: "externaldrive.badge.exclamationmark")
                } description: {
                    Text(String(localized: String.LocalizationValue(errorKey), bundle: AppLocalization.shared.bundle))
                } actions: {
                    Button(L10n.retry) { startup.start() }
                }
            } else {
                ProgressView()
            }
        }
        .task { startup.start() }
    }
}
