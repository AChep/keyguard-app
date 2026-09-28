import SwiftUI
import KeyguardShared

struct GeneratorOptionsToolbarButton: View {
    @Environment(GeneratorModel.self) private var generatorModel
    let options: [GeneratorActionSnapshot]
    let canOpenHistory: Bool

    var body: some View {
        Menu {
            if canOpenHistory {
                Button(L10n.generatorhistoryHeaderTitle, systemImage: "clock.arrow.circlepath") {
                    generatorModel.invokeGeneratorAction(id: "history")
                }
                if !options.isEmpty {
                    Divider()
                }
            }
            ForEach(options, id: \.id) { option in
                Button {
                    generatorModel.invokeGeneratorAction(id: option.id)
                } label: {
                    if option.selected {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Text(option.title)
                    }
                }
            }
        } label: {
            Label(L10n.moreActions, systemImage: "ellipsis.circle")
        }
    }
}
