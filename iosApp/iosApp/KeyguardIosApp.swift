import SwiftUI
import KeyguardUI

@main
struct KeyguardIosApp: App {
    @State private var model = AppViewModel.shared
    @Environment(\.scenePhase) private var scenePhase

    private var preferredColorScheme: ColorScheme? {
        switch model.appPreferences.theme {
        case "dark": .dark
        case "light": .light
        default: nil
        }
    }

    var body: some Scene {
        WindowGroup {
            RootContainer {
                KeyguardRootiOS()
            }
            .keyguardAppConfiguration(model: model, scenePhase: scenePhase)
            .preferredColorScheme(preferredColorScheme)
        }
    }
}
