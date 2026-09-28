import SwiftUI
import AppKit
import KeyguardUI

struct ContentView: View {
    @Environment(AppViewModel.self) private var model

    /// Whether the unlocked app (with its per-screen titlebar titles) is showing.
    private var isUnlocked: Bool {
        if case .unlocked = model.status { return true }
        return false
    }

    var body: some View {
        RootContainer {
            MainView()
        }
        .background {
            WindowTitleBarConfigurator(titleHidden: !isUnlocked)
        }
    }
}

private struct WindowTitleBarConfigurator: NSViewRepresentable {
    var titleHidden: Bool

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async { apply(to: view.window) }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async { apply(to: nsView.window) }
    }

    private func apply(to window: NSWindow?) {
        guard let window else { return }
        window.titleVisibility = titleHidden ? .hidden : .visible
        window.titlebarAppearsTransparent = titleHidden
    }
}
