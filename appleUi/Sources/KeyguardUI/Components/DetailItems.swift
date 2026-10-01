import SwiftUI
import KeyguardShared
#if canImport(AppKit)
import AppKit
#endif
#if canImport(UIKit)
import UIKit
#endif

struct DetailForm<Header: View>: View {
    let items: [VaultItemSnapshot]
    let invoke: (String) -> Void
    private let header: Header?

    init(items: [VaultItemSnapshot], invoke: @escaping (String) -> Void, @ViewBuilder header: () -> Header) {
        self.items = items
        self.invoke = invoke
        self.header = header()
    }

    init(items: [VaultItemSnapshot], invoke: @escaping (String) -> Void) where Header == EmptyView {
        self.items = items
        self.invoke = invoke
        self.header = nil
    }

    var body: some View {
        let groups = groups
        Form {
            if let header, groups.isEmpty || groups.first?.title != nil {
                Section { header }
            }
            ForEach(groups) { group in
                Section {
                    if group.title == nil, group.id == groups.first?.id, let header {
                        header
                    }
                    rows(group)
                } header: {
                    if let title = group.title {
                        Text(title)
                    }
                } footer: {
                    if !group.labels.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(group.labels, id: \.id) { item in
                                DetailRow(item: item, invoke: invoke)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder
    private func rows(_ group: Group) -> some View {
        ForEach(group.rows, id: \.id) { item in
            if item.kind == .value || item.kind == .totp {
                DetailRow(item: item, sectionTitle: group.sectionTitle, invoke: invoke)
                    .compactControlRowInsets(4)
            } else {
                DetailRow(item: item, sectionTitle: group.sectionTitle, invoke: invoke)
            }
        }
    }

    private struct Group: Identifiable {
        let id: String
        let title: String?
        let sectionTitle: String?
        let rows: [VaultItemSnapshot]
        let labels: [VaultItemSnapshot]
    }

    // Supporting labels belong in native section footers. If fields follow a
    // label, continue in a new card without repeating the section heading.
    private var groups: [Group] {
        var result: [Group] = []
        var currentTitle: String?
        var sectionTitle: String?
        var currentRows: [VaultItemSnapshot] = []
        var currentLabels: [VaultItemSnapshot] = []
        var pendingSpacers: [VaultItemSnapshot] = []

        func flush() {
            if let first = currentRows.first ?? currentLabels.first {
                result.append(
                    Group(
                        id: first.id, title: currentTitle, sectionTitle: sectionTitle,
                        rows: currentRows, labels: currentLabels))
            }
            currentRows = []
            currentLabels = []
            pendingSpacers = []
        }

        for item in items {
            if item.kind == VaultItemKind.section {
                flush()
                let title = item.title
                currentTitle = (title?.isEmpty == false) ? title : nil
                sectionTitle = currentTitle
            } else if item.kind == VaultItemKind.label {
                if let text = item.text, !text.isEmpty {
                    currentLabels.append(item)
                    pendingSpacers = []
                }
            } else if item.kind == VaultItemKind.spacer {
                // Native section spacing replaces spacers next to a footer.
                pendingSpacers.append(item)
            } else if item.kind == VaultItemKind.tags && item.actions.isEmpty {
                // An empty pill collection must not create an empty form card.
                continue
            } else if item.kind != VaultItemKind.unsupported {
                if !currentLabels.isEmpty {
                    flush()
                    currentTitle = nil
                }
                currentRows.append(contentsOf: pendingSpacers)
                pendingSpacers = []
                currentRows.append(item)
            }
        }
        flush()
        return result
    }
}

struct DetailHeaderBar<Leading: View, Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            leading()
            VStack(alignment: .leading, spacing: 2) {
                Text(title.isEmpty ? L10n.credentialExchangeImportUntitled : title)
                    .font(.title2.weight(.semibold))
                    .textSelection(.enabled)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .top) {
            Rectangle()
                .fill(.ultraThinMaterial)
                .mask(
                    LinearGradient(
                        stops: [
                            .init(color: .black, location: 0.0),
                            .init(color: .black, location: 0.72),
                            .init(color: .clear, location: 1.0),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .ignoresSafeArea()
        }
    }
}

/// A detail screen: the shared `DetailForm` of the producer's items under a
/// `DetailHeaderBar`, with the screen's actions placed where the platform expects
/// them — inside the custom header on macOS, hoisted into the navigation bar on
/// iOS. macOS account details use this pinned-header layout; cipher, Send, and
/// iOS account details use a scrolling identity header in `DetailForm`.
struct DetailScaffold<Icon: View, Actions: View>: View {
    let title: String
    var subtitle: String? = nil
    let items: [VaultItemSnapshot]
    let invoke: (String) -> Void
    @ViewBuilder var icon: () -> Icon
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        DetailForm(items: items, invoke: invoke)
            .safeAreaInset(edge: .top, spacing: 0) {
                DetailHeaderBar(title: title, subtitle: subtitle, leading: icon) {
                    #if os(macOS)
                    actions()
                    #endif
                }
            }
            #if os(iOS)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                actions()
            }
        }
            #endif
    }
}

/// The tinted SF Symbol a detail header shows when there is no favicon — sized to
/// match `FaviconView`'s 36pt slot.
struct DetailHeaderSymbol: View {
    let systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.title)
            .foregroundStyle(.tint)
            .frame(width: 36)
            .accessibilityHidden(true)
    }
}

struct DetailRow: View {
    let item: VaultItemSnapshot
    var sectionTitle: String? = nil
    let invoke: (String) -> Void

    var body: some View {
        row(item)
    }

    // Kotlin enums bridge to Swift as classes (compared with `==`), not Swift
    // enums, so dispatch on `kind` with equality rather than a `switch`.
    @ViewBuilder
    private func row(_ item: VaultItemSnapshot) -> some View {
        let kind = item.kind
        if kind == VaultItemKind.value {
            valueRow(item)
        } else if kind == VaultItemKind.totp {
            totpRow(item)
        } else if kind == VaultItemKind.uri {
            uriRow(item)
        } else if kind == VaultItemKind.note {
            noteRow(item)
        } else if kind == VaultItemKind.label {
            labelRow(item)
        } else if kind == VaultItemKind.toggle {
            switchRow(item)
        } else if kind == VaultItemKind.alert, let severity = item.alertSeverity {
            DetailAlertRow(item: item, severity: severity, invoke: invoke)
        } else if kind == VaultItemKind.action
            || kind == VaultItemKind.button
            || kind == VaultItemKind.alert
        {
            actionRow(item)
        } else if kind == VaultItemKind.passkey {
            passkeyRow(item)
        } else if kind == VaultItemKind.tags {
            tagsRow(item)
                #if os(iOS)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
                #endif
        } else if kind == VaultItemKind.folder {
            folderRow(item)
        } else if kind == VaultItemKind.organization {
            navRow(item, icon: "building.2")
        } else if kind == VaultItemKind.collection {
            navRow(item, icon: "rectangle.stack")
        } else if kind == VaultItemKind.quickActions {
            quickActionsRow(item)
        } else if kind == VaultItemKind.quickBadges {
            quickBadgesRow(item)
        } else if kind == VaultItemKind.attachment {
            attachmentRow(item)
        } else if kind == VaultItemKind.qr {
            qrRow(item)
        } else if kind == VaultItemKind.card {
            cardRow(item)
        } else if kind == VaultItemKind.identity {
            identityRow(item)
        } else if kind == VaultItemKind.spacer {
            spacerRow(item)
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private func valueRow(_ item: VaultItemSnapshot) -> some View {
        ValueFieldCell(item: item, invoke: invoke)
    }

    @ViewBuilder
    private func totpRow(_ item: VaultItemSnapshot) -> some View {
        DetailTotpRow(item: item, invoke: invoke)
    }

    @ViewBuilder
    private func uriRow(_ item: VaultItemSnapshot) -> some View {
        DetailUriRow(item: item, invoke: invoke)
    }

    @ViewBuilder
    private func noteRow(_ item: VaultItemSnapshot) -> some View {
        if let text = item.text, !text.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if sectionTitle != L10n.notes {
                    Text(L10n.notes)
                        .accessibilityAddTraits(.isHeader)
                }
                noteText(text, markdown: item.markdown)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    @ViewBuilder
    private func noteText(_ text: String, markdown: Bool) -> some View {
        if markdown {
            MarkdownTextView(text: text)
        } else {
            Text(text)
        }
    }

    @ViewBuilder
    private func labelRow(_ item: VaultItemSnapshot) -> some View {
        if let text = item.text, !text.isEmpty {
            Text(text)
                .font(.footnote)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func switchRow(_ item: VaultItemSnapshot) -> some View {
        LabeledContent {
            HStack(spacing: 8) {
                Toggle(item.title ?? "", isOn: .constant(item.switchValue))
                    .labelsHidden()
                    .disabled(true)
                    .toggleStyle(.switch)
                DetailActionMenu(actions: item.actions, invoke: invoke)
            }
        } label: {
            Text(item.title ?? "")
                .accessibilityHidden(true)
        }
        .labeledContentStyle(AdaptiveDetailLabeledContentStyle())
    }

    @ViewBuilder
    private func actionRow(_ item: VaultItemSnapshot) -> some View {
        DetailActionRow(item: item, invoke: invoke)
    }

    @ViewBuilder
    private func passkeyRow(_ item: VaultItemSnapshot) -> some View {
        DetailAccessoryRow {
            clickableRowContent(clickActionId: item.clickActionId) {
                DetailRowLabel(
                    systemImage: "key.fill",
                    title: item.title?.isEmpty == false ? item.title! : L10n.passkey,
                    subtitle: item.text,
                    disclosure: item.clickActionId == nil ? nil : "chevron.forward"
                )
            }
        } accessories: {
            BorderedActionButtons(actions: item.actions, invoke: invoke)
        }
    }

    @ViewBuilder
    private func tagsRow(_ item: VaultItemSnapshot) -> some View {
        FlowLayout(spacing: 8, clampsToWidth: true) {
            ForEach(item.actions, id: \.id) { action in
                Button {
                    invoke(action.id)
                } label: {
                    Text(action.title)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(.quaternary, in: Capsule())
                        .touchTarget()
                }
                .buttonStyle(.plain)
            }
        }
    }

    // Folder breadcrumb: every parent node navigates to its own folder, the
    // last node is the folder itself (same target as the row onClick).
    @ViewBuilder
    private func folderRow(_ item: VaultItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "folder")
                .foregroundStyle(.secondary)
                .frame(width: 20)
                .accessibilityHidden(true)
            FlowLayout(spacing: 4, clampsToWidth: true) {
                ForEach(Array(item.actions.enumerated()), id: \.element.id) { index, node in
                    if index > 0 {
                        Image(systemName: "chevron.compact.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                    if index == item.actions.count - 1 {
                        Button {
                            invoke(item.clickActionId ?? node.id)
                        } label: {
                            Text(node.title).touchTarget()
                        }
                        .buttonStyle(.plain)
                        .breadcrumbAffordance(node.title)
                    } else {
                        Button {
                            invoke(node.id)
                        } label: {
                            Text(node.title).touchTarget()
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                        .breadcrumbAffordance(node.title)
                    }
                }
            }
            Spacer(minLength: 0)
            if item.clickActionId != nil {
                Image(systemName: "chevron.forward")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // Organization / collection rows: icon + title + chevron.
    @ViewBuilder
    private func navRow(_ item: VaultItemSnapshot, icon: String) -> some View {
        clickableRowContent(clickActionId: item.clickActionId) {
            DetailRowLabel(
                systemImage: icon,
                title: item.title ?? "",
                disclosure: item.clickActionId == nil ? nil : "chevron.forward"
            )
        }
    }

    @ViewBuilder
    private func quickActionsRow(_ item: VaultItemSnapshot) -> some View {
        FlowLayout(spacing: 8, clampsToWidth: true) {
            BorderedActionButtons(actions: item.actions, invoke: invoke)
        }
    }

    @ViewBuilder
    private func quickBadgesRow(_ item: VaultItemSnapshot) -> some View {
        FlowLayout(spacing: 8, clampsToWidth: true) {
            ForEach(Array(item.badges.enumerated()), id: \.offset) { _, badge in
                HStack(spacing: 4) {
                    Text(badge.title)
                    if let text = badge.text, !text.isEmpty {
                        Text(text)
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.callout)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(.quaternary, in: Capsule())
            }
        }
    }

    @ViewBuilder
    private func attachmentRow(_ item: VaultItemSnapshot) -> some View {
        DetailAccessoryRow {
            clickableRowContent(clickActionId: item.clickActionId) {
                VStack(alignment: .leading, spacing: 4) {
                    DetailRowLabel(
                        systemImage: "doc",
                        title: item.title ?? "",
                        subtitle: item.text,
                        disclosure: item.clickActionId == nil ? nil : "eye"
                    )
                    attachmentStatus(item.attachment)
                        .padding(.leading, 32)
                }
            }
            .modifier(DetailAttachmentAccessibility(item: item, invoke: invoke))
        } accessories: {
            if let urlString = item.launchUrl {
                DetailIconButton(title: L10n.openAction, systemImage: "arrow.up.forward.app") {
                    openLocalFile(urlString)
                }
            }
            DetailActionMenu(actions: item.actions, invoke: invoke)
        }
    }

    @ViewBuilder
    private func attachmentStatus(_ status: AttachmentFieldSnapshot?) -> some View {
        if let status {
            if status.status == AttachmentStatusKind.loading {
                HStack(spacing: 6) {
                    if status.progress >= 0 {
                        ProgressView(value: Double(status.progress))
                            .frame(width: 120)
                    } else {
                        ProgressView()
                            .controlSize(.small)
                    }
                    if let downloaded = status.downloadedText {
                        Text(downloaded)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else if status.status == AttachmentStatusKind.failed {
                Label(
                    status.autoResume ? L10n.fileStatusDownloadFailedAutoResuming : L10n.fileStatusDownloadingFailed,
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.caption)
                .foregroundStyle(.red)
            } else if status.status == AttachmentStatusKind.downloaded {
                Label(L10n.fileStatusDownloaded, systemImage: "checkmark.circle")
                    .font(.caption)
                    .foregroundStyle(.green)
            } else if status.status == AttachmentStatusKind.pendingUpload {
                Text(L10n.fileStatusPendingUpload)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // The Wi-Fi network QR code; drawn natively from the producer's payload by
    // the same renderer as the "Show as Barcode" dialog.
    @ViewBuilder
    private func qrRow(_ item: VaultItemSnapshot) -> some View {
        HStack {
            Spacer(minLength: 0)
            DetailQRCode(text: item.text ?? "")
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func cardRow(_ item: VaultItemSnapshot) -> some View {
        CardFieldCell(item: item, invoke: invoke)
    }

    @ViewBuilder
    private func identityRow(_ item: VaultItemSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title = item.title, !title.isEmpty {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            if let name = item.text, !name.isEmpty {
                Text(name)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            FlowLayout(spacing: 8, clampsToWidth: true) {
                ForEach(item.actions, id: \.id) { action in
                    Button {
                        invoke(action.id)
                    } label: {
                        if let iconName = action.iconName, !iconName.isEmpty {
                            Label(action.title, systemImage: iconName)
                        } else {
                            Text(action.title)
                        }
                    }
                    // A form's automatic label style mismeasures the text when
                    // the flow asks for each button's ideal size.
                    .labelStyle(.titleAndIcon)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .fixedSize(horizontal: false, vertical: true)
                    .touchTarget()
                    .help(action.title)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func spacerRow(_ item: VaultItemSnapshot) -> some View {
        Color.clear
            .frame(height: CGFloat(item.spacerHeight))
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .accessibilityHidden(true)
    }

    // Wraps the row content in a plain full-width button when the bridge
    // registered a row-level click handler; renders it plain otherwise.
    @ViewBuilder
    private func clickableRowContent<RowContent: View>(
        clickActionId: String?,
        @ViewBuilder content: () -> RowContent
    ) -> some View {
        if let clickActionId {
            Button {
                invoke(clickActionId)
            } label: {
                content()
                    .touchTarget()
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // Downloaded attachments hand back a local path or file URL.
    private func openLocalFile(_ raw: String) {
        let url: URL? =
            raw.hasPrefix("/")
            ? URL(fileURLWithPath: raw)
            : URL(string: raw)
        if let url {
            #if os(macOS)
            NSWorkspace.shared.open(url)
            #else
            UIApplication.shared.open(url)
            #endif
        }
    }
}

private extension View {
    @ViewBuilder
    func breadcrumbAffordance(_ title: String) -> some View {
        #if os(macOS)
        self
            .foregroundStyle(.tint)
            .help(title)
        #else
        self.help(title)
        #endif
    }
}

// MARK: - Reusable cells

// MARK: - TOTP badge

private struct DetailTotpProviderKey: EnvironmentKey {
    static let defaultValue: (@MainActor @Sendable (String) -> TotpFieldSnapshot?)? = nil
}

extension EnvironmentValues {
    /// Looks up the live TOTP badge of a detail row by its id. Set by screens that
    /// receive the countdown on a separate channel (cipher detail); `nil` falls back
    /// to the row's own `totp`.
    var detailTotpProvider: (@MainActor @Sendable (String) -> TotpFieldSnapshot?)? {
        get { self[DetailTotpProviderKey.self] }
        set { self[DetailTotpProviderKey.self] = newValue }
    }
}

/// A TOTP field row. Evaluating `detailTotpProvider` inside this body is what
/// scopes the per-second invalidation to this row.
private struct DetailTotpRow: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void
    @Environment(\.detailTotpProvider) private var totpProvider

    var body: some View {
        let totp = totpProvider?(item.id) ?? item.totp
        FieldCell(
            title: item.title,
            actions: item.actions,
            invoke: invoke,
            accessibilityValueOverride: totp.flatMap { $0.isError ? L10n.errorInvalidKey : $0.codeRaw }
        ) {
            if let totp {
                TotpBadgeView(totp: totp, showsBackground: false)
            } else {
                ProgressView().controlSize(.small)
            }
        }
    }
}
