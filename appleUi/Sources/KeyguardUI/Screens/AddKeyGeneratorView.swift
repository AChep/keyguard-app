import SwiftUI
import KeyguardShared

/// A step inside the existing item editor, with no independent presentation or draft lifetime.
struct AddKeyGeneratorView: View {
    let model: AddKeyGeneratorModel

    private var generator: GeneratorSnapshot { model.snapshot.generator }

    var body: some View {
        if generator.types.isEmpty {
            LoadingIndicator()
        } else {
            Form {
                generatorOptionSections(filters: generator.filters, length: nil, actions: model.actions)
                    .disabled(!generator.loaded)
                Section {
                    if !generator.loaded {
                        ProgressView(L10n.loading)
                    } else if model.snapshot.canUseKey, let value = generator.value {
                        if let userId = model.snapshot.userId, !userId.isEmpty {
                            LabeledContent(L10n.genericName, value: userId)
                        }
                        if let title = value.title {
                            LabeledContent(L10n.type, value: title)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L10n.fingerprint)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(value.value)
                                .font(.body.monospaced())
                                .textSelection(.enabled)
                        }
                    }
                    Button(
                        model.snapshot.canUseKey ? L10n.generatorRegenerateButton : L10n.generatorGenerateButton,
                        systemImage: "arrow.clockwise"
                    ) {
                        model.actions.invoke("value:refresh")
                    }
                    .labelStyle(.titleAndIcon)
                    .disabled(!generator.loaded || generator.value?.canRefresh != true)
                }
            }
            .formStyle(.grouped)
        }
    }
}
