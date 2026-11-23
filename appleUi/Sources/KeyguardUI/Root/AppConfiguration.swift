import SwiftUI

public extension View {
    #if os(iOS)
    func keyguardAppConfiguration(model: AppViewModel, scenePhase: ScenePhase) -> some View {
        modifier(KeyguardAppConfiguration(model: model, scenePhase: scenePhase))
    }
    #else
    /// The app delegate reports the macOS lifecycle from AppKit window state.
    func keyguardAppConfiguration(model: AppViewModel) -> some View {
        modifier(KeyguardAppConfiguration(model: model))
    }
    #endif
}

private struct KeyguardAppConfiguration: ViewModifier {
    let model: AppViewModel
    #if os(iOS)
    let scenePhase: ScenePhase
    #endif

    func body(content: Content) -> some View {
        content
            #if os(macOS)
        // Menu actions keep their confirmation outside the root's sheets,
        // separate from the unlock screen's locally owned confirmation.
        .unlockActionConfirmation(
            Binding(
                get: { model.pendingUnlockAction },
                set: { model.pendingUnlockAction = $0 }
            ))
            #elseif os(iOS)
        .modifier(LinkOpeningModifier(links: model.links))
        .background(PrivacyScreen().allowsHitTesting(false).accessibilityHidden(true))
            #endif
            .keyguardEnvironment(model: model)
            .tint(model.accentColor)
            #if os(iOS)
        .onOpenURL { model.handleDeepLink($0) }
        .onChange(of: scenePhase) { _, phase in model.updateScenePhase(phase) }
        #endif
    }
}
