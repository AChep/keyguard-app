import SwiftUI
import KeyguardUI

@main
struct KeyguardMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var startup = AppStartup.shared
    @AppStorage(DockIconMode.menuBarOnlyKey) private var menuBarOnly = false

    var body: some Scene {
        // Keep the menu-bar scene first: SwiftUI builds the first scene on launch,
        // and the vault window must wait for the launch reason and preferences.
        MenuBarExtra {
            AppStartupView(startup: startup) { model in
                MenuBarPopover()
                    .keyguardEnvironment(model: model)
                    .tint(model.accentColor)
            }
        } label: {
            MainWindowLauncher { open in appDelegate.installMainWindowOpener(open) }
        }
        .menuBarExtraStyle(.window)
        .onChange(of: menuBarOnly) {
            DockIconMode.apply(menuBarOnly: menuBarOnly)
        }

        WindowGroup(id: "main") {
            #if KEYGUARD_STARTUP_TESTING
            let _ = StartupTestSupport.record("main-root-created")
            #endif
            AppStartupView(startup: startup) { model in
                ContentView()
                    .keyguardAppConfiguration(model: model)
            }
            .frame(minWidth: 460, minHeight: 560)
            .onAppear { appDelegate.mainWindowDidOpen() }
        }
        .windowResizability(.contentSize)
        .windowStyle(.titleBar)
        .commands {
            if let model = startup.model { VaultCommands(model: model) }
            ToolbarCommands()
            #if KEYGUARD_STARTUP_TESTING
            StartupTestCommands()
            #endif
        }
    }
}
