import SwiftUI

@main
struct KeyguardIosApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeHostView()
                .ignoresSafeArea(.container, edges: .all)
                .ignoresSafeArea(.keyboard)
        }
    }
}
