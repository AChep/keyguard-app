import SwiftUI

/// The menu-bar label is present without opening its popover or the vault scene.
struct MainWindowLauncher: View {
    @Environment(\.openWindow) private var openWindow
    let install: (@escaping () -> Void) -> Void

    var body: some View {
        Label("Keyguard", systemImage: "lock.shield")
            .onAppear {
                #if KEYGUARD_STARTUP_TESTING
                StartupTestSupport.record("opener-installed")
                #endif
                install { openWindow(id: "main") }
            }
    }
}
