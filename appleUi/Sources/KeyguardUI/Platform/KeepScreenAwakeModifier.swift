import SwiftUI

extension View {
    /// Large text and barcodes ignore the preference, matching Android behavior.
    func keepScreenAwake(requiresPreference: Bool = true) -> some View {
        #if os(iOS)
        modifier(KeepScreenAwakeModifier(requiresPreference: requiresPreference))
        #else
        self
        #endif
    }
}

#if os(iOS)
private struct KeepScreenAwakeModifier: ViewModifier {
    @Environment(ScreenAwakeCoordinator.self) private var coordinator
    @Environment(AppPreferencesModel.self) private var preferences
    @Environment(VaultSessionModel.self) private var auth
    @Environment(\.scenePhase) private var scenePhase
    @State private var id = UUID()
    @State private var isVisible = false

    let requiresPreference: Bool

    private var request: ScreenAwakeRequest {
        ScreenAwakeRequest(
            isVisible: isVisible,
            isSceneActive: scenePhase == .active,
            isUnlocked: auth.status == .unlocked,
            isEnabled: !requiresPreference
                || (preferences.appPreferences.loaded && preferences.appPreferences.keepScreenOn)
        )
    }

    func body(content: Content) -> some View {
        content
            .onAppear {
                isVisible = true
                coordinator.update(id: id, request: request)
            }
            .onChange(of: request) { _, _ in
                coordinator.update(id: id, request: request)
            }
            .onDisappear {
                isVisible = false
                coordinator.remove(id: id)
            }
    }
}
#endif
