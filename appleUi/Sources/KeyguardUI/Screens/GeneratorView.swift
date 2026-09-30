import SwiftUI
import KeyguardShared

struct GeneratorView: View {
    @Environment(FilePickerModel.self) private var filePickerModel
    @Environment(GeneratorModel.self) private var generatorModel

    private var generator: GeneratorSnapshot { generatorModel.generator }

    private var actions: GeneratorActions {
        GeneratorActions(
            invoke: { generatorModel.invokeGeneratorAction(id: $0) },
            setSwitch: { generatorModel.setGeneratorSwitch(key: $0, value: $1) },
            setCounter: { generatorModel.setGeneratorCounter(key: $0, value: $1) },
            setText: { generatorModel.setGeneratorText(key: $0, text: $1) },
            setLength: { generatorModel.setGeneratorLength($0) }
        )
    }

    var body: some View {
        // The generator tools (email forwarders / wordlists / history) and the
        // per-wordlist detail are pushed through the shared Kotlin nav stack, so the
        // section hosts a `NavStackContainer` instead of a plain `NavigationStack`.
        NavStackContainer(scope: "generator") {
            Group {
                // `loaded` describes each value-generation operation, including
                // those triggered by typing. Keep the form mounted during these
                // operations so its text fields retain their buffers and focus.
                if generator.types.isEmpty {
                    LoadingIndicator()
                } else {
                    content
                }
            }
            .navigationTitle(L10n.generatorHeaderTitle)
            .toolbar { generatorToolbar }
        }
        .sheet(
            item: Binding(
                get: { filePickerModel.pendingDatePicker.flatMap { $0.request.presentsInAddForm ? nil : $0 } },
                set: { if $0 == nil { filePickerModel.cancelDatePicker() } }
            )
        ) { pending in
            DatePickerSheet(request: pending.request)
        }
        .observing(
            start: { generatorModel.startGeneratorObservation() },
            stop: { generatorModel.stopGeneratorObservation() }
        )
        // The generated value's "create login / SSH key" action emits an AddRoute;
        // the prefilled create-item sheet it surfaces is presented at the root
        // (`RootContainer`) so it works from any screen, not just here.
    }

    private var content: some View {
        Form {
            Section { GeneratorTypePicker(types: generator.types, actions: actions) }
            // Reserve the output row while validation/generation has no value.
            // Inserting a whole section above the inputs makes native Form
            // recreate their rows and drops the active field's first responder.
            valueSection(generator.value)
            if !generator.suggestions.isEmpty {
                suggestionsSection
            }
            if let tip = generator.tip {
                tipSection(tip)
            }
            generatorOptionSections(
                filters: generator.filters,
                length: generator.length,
                actions: actions
            )
        }
        .formStyle(.grouped)
    }

    // MARK: - Toolbar

    #if os(macOS)
    @ToolbarContentBuilder
    private var generatorToolbar: some CustomizableToolbarContent {
        ToolbarItem(id: "generator.refresh") {
            GeneratorRefreshToolbarButton()
        }
        if generatorModel.generatorOptionsToolbar.canOpenHistory
            || !generatorModel.generatorOptionsToolbar.options.isEmpty
        {
            ToolbarItem(id: "generator.more") {
                GeneratorOptionsToolbarButton(
                    options: generatorModel.generatorOptionsToolbar.options,
                    canOpenHistory: generatorModel.generatorOptionsToolbar.canOpenHistory
                )
            }
        }
    }
    #else
    @ToolbarContentBuilder
    private var generatorToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            GeneratorRefreshToolbarButton()
        }
        ToolbarItem(placement: .topBarTrailing) {
            if generator.canOpenHistory || !generator.options.isEmpty {
                GeneratorOptionsToolbarButton(options: generator.options, canOpenHistory: generator.canOpenHistory)
            }
        }
    }
    #endif

    // MARK: - Generated value

    private func valueSection(_ value: GeneratorValueSnapshot?) -> some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Group {
                    if let value, !value.value.isEmpty {
                        PasswordText(value.value)
                            .textSelection(.enabled)
                    } else {
                        // Keep the placeholder meaningful to VoiceOver without
                        // overriding the label of AppKit-backed selectable text.
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
                if let value {
                    valueButtons(value)
                }
            }
        } header: {
            if let title = value?.title, !title.isEmpty {
                Text(title)
            }
        }
    }

    @ViewBuilder
    private func valueButtons(_ value: GeneratorValueSnapshot) -> some View {
        if value.canCopy {
            DetailIconButton(title: L10n.copy, systemImage: "doc.on.doc") {
                generatorModel.invokeGeneratorAction(id: "value:copy")
            }
            // Copying an empty placeholder would put an empty string on the
            // clipboard; gate the action on an actual generated value.
            .disabled(value.value.isEmpty)
        }
        if !value.actions.isEmpty {
            Menu {
                ForEach(value.actions, id: \.id) { action in
                    Button(action.title) { generatorModel.invokeGeneratorAction(id: action.id) }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .touchTarget()
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .accessibilityLabel(L10n.moreActions)
        }
    }

    // MARK: - Suggestions

    private var suggestionsSection: some View {
        Section(L10n.generatorSuggestionsTitle) {
            ForEach(generator.suggestions, id: \.id) { suggestion in
                Button {
                    generatorModel.invokeGeneratorAction(id: suggestion.id)
                } label: {
                    HStack {
                        PasswordText(suggestion.value)
                            .font(.body.monospaced())
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Image(systemName: "doc.on.doc")
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Tip

    private func tipSection(_ tip: GeneratorTipSnapshot) -> some View {
        Section {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "lightbulb")
                    .foregroundStyle(.tint)
                Text(tip.text)
                    .font(.callout)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if tip.canHide {
                    DetailIconButton(title: L10n.hide, systemImage: "xmark") {
                        generatorModel.invokeGeneratorAction(id: "tip:hide")
                    }
                }
            }
            if tip.canLearnMore {
                Button(L10n.learnMore) { generatorModel.invokeGeneratorAction(id: "tip:learnMore") }
                    #if os(macOS)
                .buttonStyle(.link)
                    #else
                .buttonStyle(.borderless)
                    #endif
            }
        }
    }

}
