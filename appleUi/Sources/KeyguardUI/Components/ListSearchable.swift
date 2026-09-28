import SwiftUI

extension View {
    /// Keep iOS list search discoverable after navigation and content loading.
    /// Automatic drawer placement can collapse the field until the user pulls down.
    func listSearchable(
        text: Binding<String>,
        prompt: Text,
        macOSPlacement: SearchFieldPlacement = .automatic
    ) -> some View {
        #if os(iOS)
        searchable(text: text, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt)
        #else
        searchable(text: text, placement: macOSPlacement, prompt: prompt)
        #endif
    }
}
