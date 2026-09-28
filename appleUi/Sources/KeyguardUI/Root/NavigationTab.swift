/// Keep every destination in the TabView with a stable identity, but expose at
/// most five tabs. The overflow menu can reveal its selected destination in the
/// fifth slot without entering the system's More navigation controller.
struct NavigationTab: Identifiable {
    let section: NavSection
    let isOverflow: Bool

    var id: String { section.key }

    func isHidden(selectedKey: String) -> Bool {
        isOverflow && section.key != selectedKey
    }

    static func items(for sections: [NavSection]) -> [NavigationTab] {
        sections.enumerated().map { index, section in
            NavigationTab(
                section: section,
                isOverflow: sections.count > 5 && index >= 4)
        }
    }
}
