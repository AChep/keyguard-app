#if os(macOS)
import SwiftUI
import KeyguardShared

/// Menu-bar quick-access popover backed by a shared vault-list session.
public struct MenuBarPopover: View {
    public init() {}

    @Environment(VaultSessionModel.self) private var authModel
    @Environment(VaultActionsModel.self) private var vaultActionsModel
    @Environment(\.openWindow) private var openWindow

    /// The self-owned session driving the popover. Created lazily on first
    /// appear from the shared `KeyguardCore` (the same instance `AppViewModel`
    /// owns), `start()`ed on appear and `stop()`ed on disappear — because this
    /// model owns its session, `stop()` closes it. Optional because the
    /// `@Environment` `KeyguardCore` is only reachable inside `body` (the
    /// `StackVaultListView` lazy-create pattern).
    @State private var listModel: VaultListSessionModel?

    /// The menu-bar session configuration. Mirrors the menu-bar vault list's
    /// original args: `vaultListScreenStateProducer(args = VaultRoute.Args(),
    /// mode = AppMode.Main)` under the `"menuvaultlist"` persistence scope. So:
    /// - `persistenceScope: "menuvaultlist"` — its own persisted query/sort/filter
    ///   memory, independent of the main list's `"vaultlist"` scope;
    /// - `main: false` — `VaultRoute.Args().main` defaults to `false` (it only
    ///   gates the deeplinked-custom-filter flow, a main-list concern the popover
    ///   never renders);
    /// - `canAddSecrets: true` — `VaultRoute.Args().canAddSecrets` defaults to
    ///   `true` (the popover renders no create affordance, so it is inert here);
    /// - no title / trash / archive / sort override / password-search — all the
    ///   `VaultRoute.Args()` defaults.
    ///
    /// The session always runs `AppMode.Main` internally, matching the
    /// controller's `mode = AppMode.Main`, so the popover shares the main list's
    /// already-decrypted cipher universe (cheap) while keeping its own scope.
    private var menuConfig: VaultListSessionConfig {
        VaultListSessionConfig(
            persistenceScope: "menuvaultlist",
            appBarTitle: "",
            appBarSubtitle: "",
            trash: 0,
            archive: 0,
            sortOverrideId: "",
            main: false,
            searchByPassword: false,
            canAddSecrets: true,
            cipherFilterId: ""
        )
    }

    /// Only real cipher rows are shown in the popover (no section / button / empty
    /// rows). Read straight off the store: iterate the structure in render
    /// order, keep the `item` entries, and hand back each row's decoded content.
    /// Rows whose content has not landed yet are simply omitted (the popover shows
    /// no skeletons — a quick picker, not the full list).
    private var items: [VaultRow] {
        guard let listModel else { return [] }
        return listModel.store.structure.entries
            .filter { $0.kind == .item }
            .compactMap { listModel.store.box(for: $0.id).row }
    }

    /// The Kotlin-owned search text (source of truth via `bridgedText`).
    private var remoteQuery: String { listModel?.header.query ?? "" }
    /// Bumped on every programmatic query write (clear / restore).
    private var remoteQueryRevision: Int32 { listModel?.header.queryRevision ?? 0 }
    /// The rendered structure revision — used to scroll-to-top and drop a stale
    /// keyboard selection when the result set changes underneath it.
    private var structureRevision: Int64 { listModel?.store.structure.revision ?? 0 }

    /// Local typing buffer; the session's query stays the source of truth through
    /// the `bridgedText` reconciliation on the field.
    @State private var query = ""

    /// Keyboard-driven selection for the results list (the row `id`). Owned
    /// Swift-side: the popover has no multi-selection channel, but copy actions
    /// take the row directly, so a local selection is sufficient and keeps the
    /// popover keyboard-navigable like Quick Search.
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
        // Producer messages (copy confirmations, quick-generate, errors) reach
        // the shared `NotificationsModel.toasts`; render them here too so they are not
        // dropped when the popover is the active surface (e.g. menu-bar-only
        // mode). Mirrors the main window's overlay — the macOS analogue of
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
                // Return copies the selected row (or the first when nothing is
                // selected yet), so a non-first result is reachable by keyboard.
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
        // bubbles up past the field's own cursor handling — the same placement
        // QuickSearchView uses for its arrow-key navigation.
        .onKeyPress(.downArrow) {
            moveSelection(1); return .handled
        }
        .onKeyPress(.upArrow) {
            moveSelection(-1); return .handled
        }
    }

    /// The currently keyboard-selected row, if it still exists in the results.
    private var selectedItem: VaultRow? {
        guard let selection else { return nil }
        return items.first { $0.id == selection }
    }

    /// Move the keyboard selection by `delta`, clamped, defaulting to the first
    /// row when nothing is selected yet.
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
        // A `selection`-bound List gives native click + arrow-key selection and a
        // highlight, so the clickable area is discoverable before the (sensitive)
        // copy action runs; copy is an explicit per-row control / Return, not a
        // bare whole-row tap.
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

/// A single result row: an explicit, labelled copy button copies the password and
/// a trailing menu offers the other quick actions. Copy actions route through the
/// shared producer so the app's clipboard-clear behaviour is honoured (no plaintext
/// handled in Swift). Selecting the row is non-destructive — copying a secret is an
/// explicit control, never an undiscoverable whole-row tap.
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
            // Explicit, visible primary affordance for the sensitive copy action,
            // so the clipboard write is discoverable rather than bound to a bare
            // whole-row tap.
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
        // The whole-row click now selects (handled by the enclosing
        // `List(selection:)`); copying the password is an explicit control above,
        // so a bare row tap no longer silently writes a secret to the clipboard.
        .contentShape(Rectangle())
    }
}

#endif
