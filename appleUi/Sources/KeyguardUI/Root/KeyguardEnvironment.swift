import SwiftUI

public extension View {
    /// Installs the same feature instances in a scene, menu bar, or native hosting root.
    func keyguardEnvironment(model: AppViewModel) -> some View {
        modifier(KeyguardEnvironment(model: model))
    }
}

private struct KeyguardEnvironment: ViewModifier {
    let model: AppViewModel

    func body(content: Content) -> some View {
        content
            .environment(model)
            .environment(model.auth)
            .environment(model.accountLogin)
            .environment(model.navigation)
            .environment(model.dialogs)
            .environment(model.filePicker)
            .environment(model.notifications)
            .environment(model.preferences)
            .environment(model.accounts)
            .environment(model.vaultActions)
            .environment(model.sessions)
            .environment(model.watchtower)
            .environment(model.emailRelay)
            .environment(model.wordlists)
            .environment(model.sshAgent)
            .environment(model.gpgAgent)
            .environment(model.quickSearch)
            .environment(model.appInformation)
            .environment(model.settings)
            .environment(model.launchAtLogin)
            .environment(model.security)
            .environment(model.autofillSettings)
            .environment(model.autofillIndex)
            .environment(model.subscriptions)
            .environment(model.navigationSettings)
            .environment(model.backups)
            #if os(iOS)
        .environment(model.screenAwake)
            #endif
    }
}
