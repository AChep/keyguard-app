import SwiftUI
import KeyguardShared

/// Snapshot-only content: layout changes never own or restart the generator session.
struct GeneratorContentView: View {
    let generator: GeneratorSnapshot
    let actions: GeneratorActions
    var editing: GeneratorEditingState? = nil
    var layout: GeneratorWorkspaceLayout = .compact
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var stacksValueActions: Bool { editing != nil && dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        if let settingsWidth = layout.settingsWidth {
            // Each native form keeps a nonzero inset so grouped rows retain their
            // system corners. Together the two 12 pt insets make the 24 pt gutter.
            HStack(alignment: .top, spacing: 0) {
                settingsForm
                    .frame(width: settingsWidth + GeneratorWorkspaceLayout.spacing)
                resultForm
            }
            .padding(.horizontal, GeneratorWorkspaceLayout.margin / 2)
            .frame(maxWidth: GeneratorWorkspaceLayout.maximumWidth)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(GroupedSurfaceStyle.background)
        } else {
            content
        }
    }

    private var settingsForm: some View {
        Form {
            Section { GeneratorTypePicker(types: generator.types, actions: actions) }
            generatorOptionSections(
                filters: generator.filters,
                length: generator.length,
                actions: actions,
                editing: editing
            )
            if let tip = generator.tip { tipSection(tip) }
        }
        .formStyle(.grouped)
        .contentMargins(.horizontal, GeneratorWorkspaceLayout.spacing / 2, for: .scrollContent)
        .accessibilityIdentifier("generator.settings")
    }

    private var resultForm: some View {
        Form {
            valueSection(generator.value)
            if !generator.suggestions.isEmpty { suggestionsSection }
        }
        .formStyle(.grouped)
        .contentMargins(.horizontal, GeneratorWorkspaceLayout.spacing / 2, for: .scrollContent)
        .accessibilityIdentifier("generator.result")
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
                actions: actions,
                editing: editing
            )
        }
        .formStyle(.grouped)
    }

    // MARK: - Generated value

    private func valueSection(_ value: GeneratorValueSnapshot?) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
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
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !generator.loaded {
                        ProgressView()
                            .controlSize(.small)
                    }
                    if !layout.isWide, !stacksValueActions, let value {
                        valueButtons(value)
                    }
                }
                if !layout.isWide, stacksValueActions, let value {
                    HStack {
                        Spacer()
                        valueButtons(value)
                    }
                }
            }
            if layout.isWide {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { wideValueButtons(value) }
                        .fixedSize(horizontal: true, vertical: false)
                    VStack(alignment: .leading, spacing: 12) { wideValueButtons(value) }
                }
                .controlSize(.large)
                .labelStyle(.titleAndIcon)
            }
        } header: {
            if let title = value?.title, !title.isEmpty {
                Text(title)
            }
        }
    }

    @ViewBuilder
    private func wideValueButtons(_ value: GeneratorValueSnapshot?) -> some View {
        if let value, value.canCopy {
            Button {
                actions.invoke("value:copy")
            } label: {
                Label(L10n.copy, systemImage: "doc.on.doc")
                    .frame(minHeight: 32)
            }
            .buttonStyle(.borderedProminent)
            .disabled(value.value.isEmpty)
        }
        Button {
            actions.invoke("value:refresh")
        } label: {
            Label(L10n.generatorRegenerateButton, systemImage: "arrow.clockwise")
                .frame(minHeight: 32)
        }
        .buttonStyle(.bordered)
        .disabled(value?.canRefresh != true)
        if let value { valueMenu(value) }
    }

    @ViewBuilder
    private func valueButtons(_ value: GeneratorValueSnapshot) -> some View {
        if value.canCopy {
            DetailIconButton(title: L10n.copy, systemImage: "doc.on.doc") {
                actions.invoke("value:copy")
            }
            // Copying an empty placeholder would put an empty string on the
            // clipboard; gate the action on an actual generated value.
            .disabled(value.value.isEmpty)
        }
        valueMenu(value)
    }

    @ViewBuilder
    private func valueMenu(_ value: GeneratorValueSnapshot) -> some View {
        if !value.actions.isEmpty {
            Menu {
                ForEach(value.actions, id: \.id) { action in
                    Button(action.title) { actions.invoke(action.id) }
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
                    actions.invoke(suggestion.id)
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
                        actions.invoke("tip:hide")
                    }
                }
            }
            if tip.canLearnMore {
                Button(L10n.learnMore) { actions.invoke("tip:learnMore") }
                    #if os(macOS)
                .buttonStyle(.link)
                    #else
                .buttonStyle(.borderless)
                    #endif
            }
        }
    }

}
