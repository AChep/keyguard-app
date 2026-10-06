import SwiftUI

/// Selection belongs to each unlocked window, while the core owns section stacks.
enum UnlockedNavigationSelection {
    static func section(for key: String?, in sections: [NavSection]) -> NavSection {
        sections.first { $0.key == key }
            ?? sections.first { $0.key == "vault" }
            ?? sections[0]
    }

    static func section(forScope scope: String, in sections: [NavSection]) -> NavSection {
        sections.first { $0.scope == scope } ?? section(for: nil, in: sections)
    }
}

struct UnlockedNavigation<Content: View>: View {
    @Environment(AccountsModel.self) private var accountsModel
    @Environment(NavigationModel.self) private var navigationModel
    @State private var selectedKey = "vault"
    @ViewBuilder var content: ([NavSection], Binding<String>, NavSection) -> Content

    private var sections: [NavSection] { navigationModel.navSections }
    private var currentSection: NavSection {
        UnlockedNavigationSelection.section(for: selectedKey, in: sections)
    }
    private var selection: Binding<String> {
        Binding(
            get: { currentSection.key },
            set: {
                selectedKey = UnlockedNavigationSelection.section(for: $0, in: sections).key
            })
    }

    var body: some View {
        content(sections, selection, currentSection)
            #if os(macOS)
        .onChange(of: navigationModel.pendingRevealSecretId) { _, value in
            if value != nil { selectedKey = "vault" }
        }
            #endif
            .onChange(of: navigationModel.pendingDeepLinkScope) { _, scope in
                if let scope {
                    let section = UnlockedNavigationSelection.section(forScope: scope, in: sections)
                    selectedKey = section.key
                    navigationModel.setNavScope(section.scope)
                    navigationModel.pendingDeepLinkScope = nil
                }
            }
            .onChange(of: sections) { _, _ in normalizeSelection() }
            .onChange(of: selectedKey) { _, _ in normalizeSelection() }
            .onAppear {
                let section = currentSection
                selectedKey = section.key
                navigationModel.startNavItemsObservation()
                #if os(macOS)
                accountsModel.startSyncStatusObservation()
                #endif
                navigationModel.startNavStackSession()
                navigationModel.setNavScope(section.scope)
                #if os(iOS)
                accountsModel.startSyncStatusObservation()
                #endif
            }
            .onDisappear {
                navigationModel.stopNavItemsObservation()
                #if os(macOS)
                accountsModel.stopSyncStatusObservation()
                #endif
                navigationModel.stopNavStackSession()
                #if os(iOS)
                accountsModel.stopSyncStatusObservation()
                #endif
            }
    }

    private func normalizeSelection() {
        let section = currentSection
        if selectedKey != section.key { selectedKey = section.key }
        navigationModel.setNavScope(section.scope)
    }
}
