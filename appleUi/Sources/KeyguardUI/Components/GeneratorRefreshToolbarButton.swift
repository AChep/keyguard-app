import SwiftUI

struct GeneratorRefreshToolbarButton: View {
    let canRefresh: Bool
    let invoke: (String) -> Void

    var body: some View {
        Button(L10n.generatorRegenerateButton, systemImage: "arrow.clockwise") {
            invoke("value:refresh")
        }
        .help(L10n.generatorRegenerateButton)
        .disabled(!canRefresh)
    }
}
