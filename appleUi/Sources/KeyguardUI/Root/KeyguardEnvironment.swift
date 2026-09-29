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
            .environment(model.login)
            .environment(model.keepass)
            .environment(model.navigation)
            .environment(model.dialogs)
            .environment(model.filePicker)
            .environment(model.notifications)
            .environment(model.preferences)
            .environment(model.accounts)
            .environment(model.cipherDetail)
            .environment(model.vaultActions)
            .environment(model.generator)
            .environment(model.generatorHistory)
            .environment(model.autofillGenerator)
            .environment(model.gpgTools)
            .environment(model.addItem)
            .environment(model.send)
            .environment(model.watchtower)
            .environment(model.directories)
            .environment(model.emailRelay)
            .environment(model.wordlists)
            .environment(model.sshAgent)
            .environment(model.gpgAgent)
            .environment(model.quickSearch)
            .environment(model.appInformation)
            .environment(model.urlRules)
            .environment(model.settings)
            .environment(model.launchAtLogin)
            .environment(model.security)
            .environment(model.autofillSettings)
            .environment(model.autofillIndex)
            .environment(model.subscriptions)
            .environment(model.changePassword)
            .environment(model.feedback)
            .environment(model.navigationSettings)
            .environment(model.backups)
            #if os(iOS)
        .environment(model.screenAwake)
            #endif
    }
}
