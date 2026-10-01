#if os(macOS)
import SwiftUI
import KeyguardShared

/// Menu-bar quick-access popover backed by a shared vault-list session.
public struct MenuBarPopover: View {
    public init() {}

    @Environment(VaultSessionModel.self) private var authModel
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    @Environment(\.openWindow) private var openWindow

    /// Created lazily on first appear: the `@Environment` core is only reachable inside
    /// `body`. This model owns its session, so `stop()` on disappear closes it.
    @State private var listModel: VaultListSessionModel?

    /// `"menuvaultlist"` keeps the popover's query/sort/filter memory separate from the
    /// main list's `"vaultlist"` scope. The session runs `AppMode.Main`, so the popover
    /// shares the main list's already-decrypted cipher universe.
    private var menuConfig: VaultListSessionConfig {
        .vaultMain(persistenceScope: "menuvaultlist", main: false)
    }

    /// Rows whose content has not landed yet are omitted: the popover shows no skeletons.
    private var items: [VaultRow] {
        guard let listModel else { return [] }
        return listModel.store.structure.entries
            .filter { $0.kind == .item }
            .compactMap { listModel.store.box(for: $0.id).row }
    }

    private var remoteQuery: String { listModel?.header.query ?? "" }
    /// Bumped on every programmatic query write (clear / restore).
    private var remoteQueryRevision: Int32 { listModel?.header.queryRevision ?? 0 }
    private var structureRevision: Int64 { listModel?.store.structure.revision ?? 0 }

    /// Local typing buffer; the session's query stays the source of truth through
    /// the `bridgedText` reconciliation on the field.
    @State private var query = ""

    /// Owned Swift-side: the popover has no multi-selection channel, but copy actions
    /// take the row directly, so a local selection is sufficient.
    @State private var selection: String?

    public var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            footer
        }
        .frame(width: 360, height: 480)
        // Producer messages reach the shared toasts; render them here too so they are not
        // dropped when the popover is the active surface (e.g. menu-bar-only mode), like
        // Compose's per-window `ToastMessageHost`.
        .overlay(alignment: .top) {
            ToastStackView()
                .padding(.horizontal, 8)
                .padding(.top, 8)
        }
        .localElevatedAccessHost(.menuBar)
        .onAppear {
            if listModel == nil {
                listModel = VaultListSessionModel(core: vaultActionsModel.keyguardCore, config: menuConfig)
            }
            listModel?.start()
        }
        .onDisappear { listModel?.stop() }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.shield")
                .foregroundStyle(.tint)
            Text("Keyguard")
                .font(.headline)
            Spacer()
            statusBadge
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }

    private var statusBadge: some View {
        Group {
            switch authModel.status {
            case .unlocked:
                Label(L10n.menuBarStatusUnlocked, systemImage: "lock.open.fill")
                    .foregroundStyle(.green)
            case .locked:
                Label(L10n.menuBarStatusLocked, systemImage: "lock.fill")
                    .foregroundStyle(.secondary)
            case .needsCreate:
                Label(L10n.menuBarStatusSetUp, systemImage: "person.crop.circle.badge.plus")
                    .foregroundStyle(.secondary)
            case .loading:
                Label(L10n.loading, systemImage: "hourglass")
                    .foregroundStyle(.secondary)
            }
        }
        .font(.caption.weight(.medium))
        .labelStyle(.titleAndIcon)
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        switch authModel.status {
        case .unlocked:
            unlockedContent
        case .locked:
            lockedState(
                title: L10n.sshAgentHistoryResponseVaultLocked,
                message: L10n.menuBarUnlockVaultHint
            )
        case .needsCreate:
            lockedState(
                title: L10n.vaultStatusNoVaultTitle,
                message: L10n.vaultSetupOpenKeyguardHint
            )
        case .loading:
            LoadingIndicator()
        }
    }

    private var unlockedContent: some View {
        VStack(spacing: 0) {
            searchField
            Divider()
            if items.isEmpty {
                ContentUnavailableView(
                    remoteQuery.isEmpty ? L10n.vaultMainSearchPlaceholder : L10n.quickSearchNoMatchesTitle,
                    systemImage: "magnifyingglass",
                    description: Text(
                        remoteQuery.isEmpty
                            ? L10n.menuBarSearchHint
                            : L10n.menuBarSearchNoMatchesText(remoteQuery))
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                resultsList
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField(L10n.vaultMainSearchPlaceholder, text: $query)
                .bridgedText(
                    $query,
                    remote: remoteQuery,
                    remoteRevision: remoteQueryRevision,
                    send: { listModel?.setQuery($0) }
                )
                .textFieldStyle(.plain)
                .onSubmit { copyPrimary(selectedItem ?? items.first) }
            if !remoteQuery.isEmpty {
                Button {
                    listModel?.clearQuery()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help(L10n.searchClearAction)
                .accessibilityLabel(L10n.searchClearAction)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        // Arrow keys move the selection while focus stays in the search field.
        // Attached to the containing row (not the TextField) so the key event
        // bubbles up past the field's own cursor handling.
        .onKeyPress(.downArrow) {
            moveSelection(1); return .handled
        }
        .onKeyPress(.upArrow) {
            moveSelection(-1); return .handled
        }
    }

    private var selectedItem: VaultRow? {
        guard let selection else { return nil }
        return items.first { $0.id == selection }
    }

    private func moveSelection(_ delta: Int) {
        let items = items
        guard !items.isEmpty else { return }
        let currentIndex = selection.flatMap { id in items.firstIndex { $0.id == id } }
        let nextIndex: Int
        if let currentIndex {
            nextIndex = min(max(currentIndex + delta, 0), items.count - 1)
        } else {
            nextIndex = delta >= 0 ? 0 : items.count - 1
        }
        selection = items[nextIndex].id
    }

    private var resultsList: some View {
        // A `selection`-bound List makes a row click select: copying a secret is an
        // explicit per-row control or Return, never a bare whole-row tap.
        List(selection: $selection) {
            ForEach(items, id: \.id) { item in
                ResultRow(
                    item: item,
                    copyPassword: { copyPrimary(item) },
                    copyUsername: { copy(item, "username") },
                    copyOtp: { copy(item, "otp") },
                    open: { openInMainWindow() }
                )
                .tag(item.id)
            }
        }
        .listStyle(.plain)
        .scrollsToTop(onChangeOf: structureRevision, topId: items.first?.id)
        // Drop a stale selection when the result set changes underneath it.
        .onChange(of: structureRevision) { _, _ in
            if let selection, !items.contains(where: { $0.id == selection }) {
                self.selection = nil
            }
        }
    }

    private func lockedState(title: String, message: String) -> some View {
        ContentUnavailableView {
            Label(title, systemImage: "lock.fill")
        } description: {
            Text(message)
        } actions: {
            Button(L10n.autofillOpenKeyguard) { openInMainWindow() }
                .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(spacing: 12) {
            Button {
                vaultActionsModel.generateAndCopyPassword()
            } label: {
                Label(L10n.generatorGenerateButton, systemImage: "dice")
            }
            .help(L10n.menuBarGeneratePasswordHint)
            .accessibilityLabel(L10n.generatorGenerateButton)
            .disabled(authModel.status != .unlocked)

            Spacer()

            if authModel.status == .unlocked {
                Button {
                    authModel.lockVault()
                } label: {
                    Label(L10n.menuBarLockAction, systemImage: "lock")
                }
                .help(L10n.menuBarLockVaultHint)
                .accessibilityLabel(L10n.menuBarLockAction)
            }

            Button {
                openInMainWindow()
            } label: {
                Label(L10n.openAction, systemImage: "macwindow")
            }
            .help(L10n.menuBarOpenMainWindowAction)
            .accessibilityLabel(L10n.openAction)
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: - Actions

    private func copyPrimary(_ item: VaultRow?) {
        copy(item, "password")
    }

    private func copy(_ item: VaultRow?, _ field: String) {
        guard let item,
            let secretId = item.secretId,
            let accountId = item.accountId
        else { return }
        vaultActionsModel.copyCipherField(secretId: secretId, accountId: accountId, field: field)
    }

    private func openInMainWindow() {
        DockIconMode.showMainWindow {
            openWindow(id: "main")
        }
    }
}

/// Copy actions route through the shared producer so the app's clipboard-clear behaviour
/// is honoured (no plaintext handled in Swift).
private struct ResultRow: View {
    let item: VaultRow
    let copyPassword: () -> Void
    let copyUsername: () -> Void
    let copyOtp: () -> Void
    let open: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "key.fill")
                .foregroundStyle(.tint)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(item.title)
                    .lineLimit(1)
                if let text = item.subtitle, !text.isEmpty {
                    Text(text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if item.flags.contains(.favourite) {
                Image(systemName: "star.fill")
                    .font(.caption2)
                    .foregroundStyle(.yellow)
            }
            Button {
                copyPassword()
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .help(L10n.copyPassword)
            .accessibilityLabel(L10n.copyPassword)
            Menu {
                Button {
                    copyPassword()
                } label: {
                    Label(L10n.copyPassword, systemImage: "key")
                }
                Button {
                    copyUsername()
                } label: {
                    Label(L10n.copyUsername, systemImage: "person")
                }
                Button {
                    copyOtp()
                } label: {
                    Label(L10n.copyOtpCode, systemImage: "clock")
                }
                Divider()
                Button {
                    open()
                } label: {
                    Label(L10n.openInKeyguardAction, systemImage: "macwindow")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help(L10n.moreActions)
            .accessibilityLabel(L10n.moreActions)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

#endif
