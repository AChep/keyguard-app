import SwiftUI
import KeyguardShared

struct AutofillGeneratorSheet: View {
    @Environment(AutofillGeneratorModel.self) private var autofillGeneratorModel
    @Environment(\.dismiss) private var dismiss

    let autofill: AddAutofillSnapshot
    let onPick: (String) -> Void

    private var generator: GeneratorSnapshot { autofillGeneratorModel.autofillGenerator }

    private var actions: GeneratorActions { autofillGeneratorModel.actions }

    private var title: String {
        if autofill.password {
            return L10n.generatorHeaderPasswordTitle
        } else if autofill.username {
            return L10n.generatorHeaderUsernameTitle
        } else {
            return L10n.generatorHeaderTitle
        }
    }

    var body: some View {
        ModalSheet(title: title, dismissLabel: L10n.cancel) {
            Group {
                if generator.types.isEmpty {
                    LoadingIndicator()
                } else {
                    content
                }
            }
        } actions: {
            Button(L10n.generatorUseButton) {
                if let v = autofillGeneratorModel.autofillGenerator.value?.value, !v.isEmpty {
                    onPick(v)
                }
                dismiss()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(autofillGeneratorModel.autofillGenerator.value?.value.isEmpty ?? true)
        } headerActions: {
            Button(L10n.generatorRegenerateButton, systemImage: "arrow.clockwise") {
                actions.invoke("value:refresh")
            }
            .labelStyle(.iconOnly)
            .help(L10n.generatorRegenerateButton)
            .disabled(generator.value?.canRefresh != true)
        }
        .observing(
            start: {
                autofillGeneratorModel.startAutofillGeneratorObservation(
                    username: autofill.username,
                    password: autofill.password,
                    uris: autofill.uris
                )
            },
            stop: { autofillGeneratorModel.stopAutofillGeneratorObservation() }
        )
    }

    private var content: some View {
        Form {
            Section { GeneratorTypePicker(types: generator.types, actions: actions) }
            // Generation also runs while typing options. Preserve the output
            // section and form rows so their buffers and keyboard focus survive.
            valueSection(generator.value)
            if !generator.suggestions.isEmpty {
                suggestionsSection
            }
            generatorOptionSections(
                filters: generator.filters,
                length: generator.length,
                actions: actions
            )
        }
        .formStyle(.grouped)
    }

    private func valueSection(_ value: GeneratorValueSnapshot?) -> some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Group {
                    if let value, !value.value.isEmpty {
                        PasswordText(value.value)
                            .textSelection(.enabled)
                    } else {
                        Text("—")
                            .accessibilityLabel(L10n.emptyValue)
                    }
                }
                .font(.title3.monospaced())
                .frame(maxWidth: .infinity, alignment: .leading)
                if !generator.loaded {
                    ProgressView()
                        .controlSize(.small)
                }
            }
        } header: {
            if let title = value?.title, !title.isEmpty {
                Text(title)
            }
        }
    }

    private var suggestionsSection: some View {
        Section(L10n.generatorSuggestionsTitle) {
            ForEach(generator.suggestions, id: \.id) { suggestion in
                Button {
                    onPick(suggestion.value)
                    dismiss()
                } label: {
                    HStack {
                        PasswordText(suggestion.value)
                            .font(.body.monospaced())
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Image(systemName: "square.and.arrow.down")
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

}
