import SwiftUI
@preconcurrency import KeyguardShared

/// The setters a generator form drives. The standalone Generator screen and the
/// in-form AutoFill generator sheet run two separate headless `GeneratorController`
/// channels, so the rows are shared and only the destinations differ.
struct GeneratorActions: Sendable {
    let invoke: @MainActor @Sendable (String) -> Void
    let setSwitch: @MainActor @Sendable (String, Bool) -> Void
    let setCounter: @MainActor @Sendable (String, Int32) -> Void
    let setText: @MainActor @Sendable (String, String) -> Void
    let setLength: @MainActor @Sendable (Int32) -> Void
}

// MARK: - Type picker

/// The generator-type `Picker`, with the shared flat type list folded into native
/// `Section`s at each `section` marker. Belongs inside a `Section` of a `Form`.
struct GeneratorTypePicker: View {
    let types: [GeneratorTypeItemSnapshot]
    let actions: GeneratorActions

    var body: some View {
        Picker(L10n.type, selection: selection) {
            // Section markers only split the list; their titles aren't shown.
            ForEach(
                snapshotListSections(
                    types, id: { $0.id },
                    sectionTitle: {
                        $0.kind == GeneratorTypeItemKind.section ? ($0.title ?? "") : nil
                    })
            ) { group in
                Section {
                    ForEach(group.items, id: \.id) { item in
                        Text(item.title ?? "").tag(item.id)
                    }
                }
            }
        }
    }

    private var selection: Binding<String> {
        Binding(
            get: {
                types.first(where: { $0.selected })?.id
                    ?? types.first(where: { $0.kind != GeneratorTypeItemKind.section })?.id
                    ?? ""
            },
            set: { actions.invoke($0) }
        )
    }
}

// MARK: - Option sections

/// The generator's option groups: the length slider (leading the first group) plus
/// every switch / text / enum filter.
///
/// A free `@ViewBuilder` function rather than a `View` type on purpose — it resolves
/// to the same `ForEach<_, _, Section<…>>` the screens used to write inline, so
/// `Form` sees exactly the section structure it did before and each builder-emitted
/// sibling stays its own form row.
@MainActor
@ViewBuilder
func generatorOptionSections(
    filters: [GeneratorFilterSnapshot],
    length: GeneratorLengthSnapshot?,
    actions: GeneratorActions
) -> some View {
    let sections = generatorOptionGroups(filters: filters, hasLength: length != nil)
    ForEach(sections) { section in
        Section {
            // The length slider has no section of its own; it leads the first
            // option group.
            if section.id == sections.first?.id, let length {
                generatorLengthRows(length, actions: actions)
            }
            ForEach(section.fields, id: \.key) { filter in
                generatorFilterRow(filter, actions: actions)
            }
        } header: {
            if let header = section.header {
                Text(header)
            }
        }
    }
}

struct GeneratorOptionGroup: Identifiable {
    let id: String
    let header: String?
    let fields: [GeneratorFilterSnapshot]
}

/// The shared filter list is flat, with `section` markers acting as group
/// boundaries; fold it into native form sections so each `Section` renders a
/// system-styled header and grouped rows.
func generatorOptionGroups(
    filters: [GeneratorFilterSnapshot],
    hasLength: Bool
) -> [GeneratorOptionGroup] {
    var sections: [GeneratorOptionGroup] = []
    var header: String?
    var fields: [GeneratorFilterSnapshot] = []
    func flush() {
        if header != nil || !fields.isEmpty {
            sections.append(GeneratorOptionGroup(id: "\(sections.count)", header: header, fields: fields))
            fields = []
        }
    }
    for filter in filters {
        if filter.kind == GeneratorFilterKind.section {
            flush()
            let text = filter.text
            header = (text?.isEmpty == false) ? text : nil
        } else {
            fields.append(filter)
        }
    }
    flush()
    // The length slider needs a home even when there are no filters.
    if sections.isEmpty, hasLength {
        sections.append(GeneratorOptionGroup(id: "0", header: nil, fields: []))
    }
    return sections
}

// MARK: - Length

@MainActor
@ViewBuilder
func generatorLengthRows(
    _ length: GeneratorLengthSnapshot,
    actions: GeneratorActions
) -> some View {
    LabeledContent(L10n.length) {
        Text("\(length.value)")
            .font(.body.monospaced())
            .monospacedDigit()
    }
    Slider(
        value: Binding<Double>(
            get: { Double(length.value) },
            set: { actions.setLength(Int32($0.rounded())) }
        ),
        in: Double(length.min)...Double(max(length.max, length.min + 1)),
        step: 1
    )
    .accessibilityLabel(L10n.length)
    .accessibilityValue(Text(length.value, format: .number))
}

// MARK: - Filters

@MainActor
@ViewBuilder
func generatorFilterRow(
    _ filter: GeneratorFilterSnapshot,
    actions: GeneratorActions
) -> some View {
    if filter.kind == GeneratorFilterKind.switchField {
        generatorSwitchRow(filter, actions: actions)
    } else if filter.kind == GeneratorFilterKind.textField {
        generatorTextRow(filter, actions: actions)
    } else if filter.kind == GeneratorFilterKind.enumField {
        generatorEnumRow(filter, actions: actions)
    }
    // `section` markers are consumed by the grouping above.
}

@MainActor
@ViewBuilder
private func generatorSwitchRow(
    _ filter: GeneratorFilterSnapshot,
    actions: GeneratorActions
) -> some View {
    let toggle = Binding<Bool>(
        get: { filter.switchValue },
        set: { actions.setSwitch(filter.key, $0) }
    )
    if let counter = filter.counter {
        // A switch with an associated min-count stepper (e.g. minimum digits):
        // the title leads, the stepper and switch trail.
        HStack(spacing: 12) {
            Text(filter.title ?? "")
            Spacer()
            generatorCounterStepper(filter.key, counter, actions: actions)
                .accessibilityLabel(filter.title ?? "")
            Toggle(filter.title ?? "", isOn: toggle)
                .labelsHidden()
                .disabled(!filter.switchEnabled)
        }
        .accessibilityElement(children: .contain)
    } else {
        Toggle(filter.title ?? "", isOn: toggle)
            .disabled(!filter.switchEnabled)
    }
    if let text = filter.text, !text.isEmpty {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
    }
}

@MainActor
private func generatorCounterStepper(
    _ key: String,
    _ counter: GeneratorCounterSnapshot,
    actions: GeneratorActions
) -> some View {
    let binding = Binding<Int>(
        get: { Int(counter.value) },
        set: { actions.setCounter("\(key):counter", Int32($0)) }
    )
    return Stepper(value: binding, in: Int(counter.min)...Int(max(counter.max, counter.min))) {
        Text("\(counter.value)")
            .font(.body.monospaced())
            .monospacedDigit()
    }
    .fixedSize()
}

@MainActor
private func generatorTextRow(
    _ filter: GeneratorFilterSnapshot,
    actions: GeneratorActions
) -> some View {
    let field = generatorTextField(filter, actions: actions)
        // The per-field local buffer must reset when this row is reused for a
        // different filter field.
        .id(filter.key)
    // Keep a text filter as one Form row. Emitting the field and optional
    // validation as siblings lets native Form rebuild its flattened rows while
    // editing, which discards the text field's first responder.
    return VStack(alignment: .leading, spacing: 4) {
        if let title = filter.title, !title.isEmpty {
            LabeledContent {
                field
                    .labelsHidden()
            } label: {
                Text(title)
            }
        } else {
            field
        }
        if let error = filter.textError, !error.isEmpty {
            Text(error)
                .font(.footnote)
                .foregroundStyle(.red)
        }
    }
}

@MainActor
private func generatorTextField(
    _ filter: GeneratorFilterSnapshot,
    actions: GeneratorActions
) -> BridgedTextField {
    // Field keys come from the shared producer; localized titles and hints
    // must not determine keyboard behavior.
    let isName = filter.key == "gpg_key.name"
    let input = BridgedTextField(
        label: filter.title ?? "",
        prompt: filter.textPlaceholder ?? "",
        text: filter.textValue,
        textRevision: filter.textRevision,
        secure: false,
        send: { actions.setText(filter.key, $0) },
        style: .automatic,
        disablesAutocorrection: !isName
    )
    #if os(iOS)
    var configuredInput = input
    configuredInput.showsClearButton = true
    configuredInput.autocapitalization = isName ? .words : .never
    if filter.key.hasSuffix(".email") {
        configuredInput.keyboard = .emailAddress
        configuredInput.contentType = .emailAddress
    } else if filter.key.hasSuffix(".domain") {
        configuredInput.keyboard = .URL
    } else if isName {
        configuredInput.contentType = .name
    }
    return configuredInput
    #else
    return input
    #endif
}

@MainActor
private func generatorEnumRow(
    _ filter: GeneratorFilterSnapshot,
    actions: GeneratorActions
) -> some View {
    Picker(
        filter.title ?? "",
        selection: Binding(
            get: { filter.enumOptions.first(where: { $0.selected })?.id ?? filter.enumOptions.first?.id ?? "" },
            set: { actions.invoke($0) }
        )
    ) {
        ForEach(filter.enumOptions, id: \.id) { option in
            Text(option.title).tag(option.id)
        }
    }
}
