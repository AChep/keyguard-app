import AppKit
import SwiftUI

struct StartupTestCommands: Commands {
    var body: some Commands {
        CommandMenu("Startup Test") {
            Button("Reveal main window") {
                StartupTestSupport.record("reopen-command-\(StartupTestSupport.delegate != nil)")
                _ = StartupTestSupport.delegate?.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false)
            }
            Button("Load preferences") {
                StartupTestSupport.record("load-preferences-command")
                StartupTestSupport.preferences.loaded = true
                StartupTestSupport.preferencesChanged?()
            }
        }
    }
}
