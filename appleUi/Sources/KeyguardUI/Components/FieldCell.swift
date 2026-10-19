import SwiftUI
import KeyguardShared

struct FieldCell<Content: View, Accessories: View, Footer: View>: View {
    let title: String?
    let actions: [VaultActionSnapshot]
    let invoke: (String) -> Void
    var colorizeTitle: Bool
    var layout: FieldCellLayout
    /// Also supplies a safe spoken value for concealed fields and custom menu labels.
    var accessibilityValueOverride: String?
    @ViewBuilder let content: () -> Content
    @ViewBuilder let accessories: () -> Accessories
    /// Shown under the value, in the column of the value.
    @ViewBuilder let footer: () -> Footer

    init(
        title: String?,
        actions: [VaultActionSnapshot],
        invoke: @escaping (String) -> Void,
        layout: FieldCellLayout = .adaptive,
        accessibilityValueOverride: String? = nil,
        colorizeTitle: Bool = false,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder accessories: @escaping () -> Accessories,
        @ViewBuilder footer: @escaping () -> Footer
    ) {
        self.title = title
        self.colorizeTitle = colorizeTitle
        self.actions = actions
        self.invoke = invoke
        self.layout = layout
        self.accessibilityValueOverride = accessibilityValueOverride
        self.content = content
        self.accessories = accessories
        self.footer = footer
    }

    var body: some View {
        Group {
            if layout == .stacked {
                VStack(alignment: .leading, spacing: 4) {
                    if let title, !title.isEmpty {
                        PasswordText(title, colorize: colorizeTitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(accessibilityValueOverride?.isEmpty == false)
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        fieldContent
                        Spacer(minLength: 0)
                        accessories()
                        copyControl
                    }
                    footer()
                }
            } else if let title, !title.isEmpty {
                LabeledContent {
                    valueColumn
                } label: {
                    PasswordText(title, colorize: colorizeTitle)
                        .accessibilityHidden(accessibilityValueOverride?.isEmpty == false)
                }
                .labeledContentStyle(AdaptiveDetailLabeledContentStyle())
            } else {
                valueColumn
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var valueColumn: some View {
        VStack(alignment: .leading, spacing: 4) {
            valueAndControls
            footer()
        }
    }

    private var valueAndControls: some View {
        DetailAccessoryRow {
            fieldContent
                .foregroundStyle(.secondary)
        } accessories: {
            accessories()
            copyControl
        }
    }

    private var fieldContent: some View {
        menuOrPlainContent
            .accessibilityElement(children: .combine)
            .modifier(FieldAccessibility(label: title, value: accessibilityValueOverride))
    }

    @ViewBuilder
    private var menuOrPlainContent: some View {
        if actions.isEmpty {
            content()
                .textSelection(.enabled)
        } else {
            Menu {
                listActionMenuItems(actions: actions) { invoke($0) }
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    content()
                    Image(systemName: "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .fixedSize(horizontal: false, vertical: true)
                .touchTarget()
                .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
        }
    }

    @ViewBuilder
    private var copyControl: some View {
        if let copy = actions.first(where: { $0.isCopy }) {
            DetailIconButton(title: copy.title, systemImage: "doc.on.doc") {
                invoke(copy.id)
            }
        }
    }
}

extension FieldCell where Footer == EmptyView {
    init(
        title: String?,
        actions: [VaultActionSnapshot],
        invoke: @escaping (String) -> Void,
        layout: FieldCellLayout = .adaptive,
        accessibilityValueOverride: String? = nil,
        colorizeTitle: Bool = false,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder accessories: @escaping () -> Accessories
    ) {
        self.init(
            title: title,
            actions: actions,
            invoke: invoke,
            layout: layout,
            accessibilityValueOverride: accessibilityValueOverride,
            colorizeTitle: colorizeTitle,
            content: content,
            accessories: accessories,
            footer: { EmptyView() }
        )
    }
}

extension FieldCell where Accessories == EmptyView, Footer == EmptyView {
    init(
        title: String?,
        actions: [VaultActionSnapshot],
        invoke: @escaping (String) -> Void,
        layout: FieldCellLayout = .adaptive,
        accessibilityValueOverride: String? = nil,
        colorizeTitle: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            title: title,
            actions: actions,
            invoke: invoke,
            layout: layout,
            accessibilityValueOverride: accessibilityValueOverride,
            colorizeTitle: colorizeTitle,
            content: content,
            accessories: { EmptyView() },
            footer: { EmptyView() }
        )
    }
}
