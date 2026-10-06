import SwiftUI

private struct VaultListBottomBarHeightKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// Additional scroll inset for custom chrome hosted above the native tab bar.
    var vaultListBottomBarHeight: CGFloat {
        get { self[VaultListBottomBarHeightKey.self] }
        set { self[VaultListBottomBarHeightKey.self] = newValue }
    }
}

private struct VaultListBottomBarModifier<Bar: View>: ViewModifier {
    let bar: Bar
    @State private var height: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .environment(\.vaultListBottomBarHeight, height)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                bar
                    .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) {
                        height = $0
                    }
            }
    }
}

extension View {
    /// A native vault list extends beneath container chrome. UIKit automatically
    /// accounts for system bars, but cannot see a SwiftUI safe-area inset's height.
    /// Forward only this custom bar's measured height as an additional scroll inset.
    func vaultListBottomBar<Bar: View>(@ViewBuilder content: () -> Bar) -> some View {
        #if os(iOS)
        modifier(VaultListBottomBarModifier(bar: content()))
        #else
        safeAreaInset(edge: .bottom, spacing: 0, content: content)
        #endif
    }
}
