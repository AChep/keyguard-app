import SwiftUI
import KeyguardShared

struct GeneratorOptionsToolbarButton: View {
    let options: [GeneratorActionSnapshot]
    let canOpenHistory: Bool
    let invoke: (String) -> Void

    var body: some View {
        Menu {
            if canOpenHistory {
                Button(L10n.generatorhistoryHeaderTitle, systemImage: "clock.arrow.circlepath") {
                    invoke("history")
                }
                if !options.isEmpty {
                    Divider()
                }
            }
            ForEach(options, id: \.id) { option in
                Button {
                    invoke(option.id)
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
