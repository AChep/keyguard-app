import SwiftUI
import KeyguardUI

@main
struct KeyguardIosApp: App {
    @State private var startup = AppStartup.shared
    @Environment(\.scenePhase) private var scenePhase

    private var preferredColorScheme: ColorScheme? {
        switch startup.model?.appPreferences.theme {
        case "dark": .dark
        case "light": .light
        default: nil
        }
    }

    var body: some Scene {
        WindowGroup {
            AppStartupView(startup: startup) { model in
                RootContainer {
                    KeyguardRootiOS()
                }
                .keyguardAppConfiguration(model: model, scenePhase: scenePhase)
            }
            .preferredColorScheme(preferredColorScheme)
        }
    }
}
