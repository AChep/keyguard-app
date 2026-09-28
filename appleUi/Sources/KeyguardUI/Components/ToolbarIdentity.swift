#if os(macOS)
import SwiftUI

/// The customizable-toolbar identity owned by the persistent main container.
///
/// The sections (Vault, Send, Generator, Watchtower) all render into the detail
/// area of one `NavigationSplitView`, so they share one window — and one
/// `NSToolbar`. Giving each section its own `.toolbar(id:)` made SwiftUI swap the
/// window's whole `NSToolbar` on every section switch, and AppKit could run a
/// layout pass mid-swap while an `NSToolbarItemViewer` still had no item. Its
/// `-minSize` then resolved to NaN and tripped an assert:
///
///     NSToolbarItemViewer's min/max size is nan. This indicates an item's size
///     has been changed during layout which is illegal
///
/// The identity must also outlive root screens when a destination is pushed.
/// Keeping it on `MainView` lets AppKit mutate a single toolbar's item set instead of
/// exchanging toolbar instances, so no viewer is ever orphaned mid-layout.
enum KeyguardToolbar {
    static let sharedId = "keyguard.main"
}
#endif
