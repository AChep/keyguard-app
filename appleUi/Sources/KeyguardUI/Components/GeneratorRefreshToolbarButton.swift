import SwiftUI

struct GeneratorRefreshToolbarButton: View {
    @Environment(GeneratorModel.self) private var generatorModel

    var body: some View {
        Button(L10n.generatorRegenerateButton, systemImage: "arrow.clockwise") {
            generatorModel.invokeGeneratorAction(id: "value:refresh")
        }
        .help(L10n.generatorRegenerateButton)
        .disabled(generatorModel.generator.value?.canRefresh != true)
    }
}
