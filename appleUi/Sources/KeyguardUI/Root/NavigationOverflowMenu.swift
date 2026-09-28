import SwiftUI

#if os(iOS)
struct NavigationOverflowMenu: View {
    let sections: [NavSection]
    @Binding var selection: String
    let showLabels: Bool

    var body: some View {
        Menu {
            ForEach(sections) { section in
                Button {
                    selection = section.key
                } label: {
                    Label(
                        section.title,
                        systemImage: selection == section.key ? "checkmark" : section.systemImage)
                }
                .accessibilityAddTraits(selection == section.key ? .isSelected : [])
            }
        } label: {
            NavigationTabLabel(title: L10n.more, systemImage: "ellipsis", showTitle: showLabels)
                .padding(.horizontal, 8)
        }
        .menuOrder(.fixed)
        .buttonStyle(.plain)
        .accessibilityIdentifier("navigation.overflow")
    }

}
#endif
