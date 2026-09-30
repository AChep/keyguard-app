import SwiftUI

public extension View {
    func keyguardAppConfiguration(model: AppViewModel, scenePhase: ScenePhase) -> some View {
        modifier(KeyguardAppConfiguration(model: model, scenePhase: scenePhase))
    }
}

private struct KeyguardAppConfiguration: ViewModifier {
    let model: AppViewModel
    let scenePhase: ScenePhase

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
            .onOpenURL { model.handleDeepLink($0) }
            .onChange(of: scenePhase) { _, phase in model.updateScenePhase(phase) }
    }
}
