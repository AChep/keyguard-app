#if os(macOS)
import SwiftUI
import AppKit
import KeyguardShared

/// Spotlight-style Quick Search overlay, mirroring the Compose two-pane quick
/// panel. A thin renderer over the shared quick-search producer: selection,
/// action cycling and the copy/open actions all run in shared Kotlin.
struct QuickSearchView: View {
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(DialogsModel.self) private var dialogsModel
    @Environment(QuickSearchModel.self) private var quickSearchModel
    let onDismiss: () -> Void
    @FocusState private var searchFocused: Bool
    @Environment(\.colorScheme) private var colorScheme

    private var snapshot: QuickSearchSnapshot { quickSearchModel.quickSearch }
    private var items: [QuickSearchResultSnapshot] { snapshot.results }

    /// Local typing buffer; the Kotlin query sink stays the source of truth
    /// through the `bridgedText` reconciliation on the field.
    @State private var query = ""

    /// Required by the shared `VaultListTableView`; unused here — Quick Search tracks its
    /// ONE selection out of band via `VaultListConfig.selectedRowId` (the producer's
    /// `selectedItemId`), so the table's native selection channel is inert.
    @State private var listSelection = VaultSelectionModel()

    /// No context menu: Quick Search uses the action strip.
    private var listConfig: VaultListConfig {
        VaultListConfig(
            rowTap: .select,
            supportsMultiSelect: false,
            contextMenu: false,
            syncHeader: false,
            quickFilters: false,
            selectedRowId: snapshot.selectedItemId,
            highlightsSelectedRowId: true
        )
    }

    var body: some View {
        content
            .frame(width: 700, height: 460)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12).strokeBorder(.separator, lineWidth: 1)
            )
            .localElevatedAccessHost(.quickSearch, isEnabled: quickSearchModel.quickSearchVisible)
            .modifier(Fido2PromptModifier(isEnabled: quickSearchModel.quickSearchVisible))
            .appToastOverlay(isEnabled: quickSearchModel.quickSearchVisible && dialogsModel.elevatedAccess == nil)
            .onAppear { searchFocused = true }
            .onChange(of: quickSearchModel.quickSearchFocusToken) { _, _ in searchFocused = true }
            // After an inline unlock, move keyboard focus to the (now mounted)
            // search field. Async so the field exists when we set focus.
            .onChange(of: authModel.status) { _, newValue in
                if newValue == .unlocked {
                    DispatchQueue.main.async { searchFocused = true }
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch authModel.status {
        case .unlocked:
            unlockedContent
        case .locked:
            // Gate on panel visibility: the hosting view is cached, so this keeps
            // the unlock form (and the Touch ID host it arms, which auto-prompts on
            // entry) tied to the panel actually being on screen.
            if quickSearchModel.quickSearchVisible {
                QuickSearchUnlock()
            } else {
                Color.clear
            }
        case .loading:
            LoadingIndicator()
        case .needsCreate:
            ContentUnavailableView {
                Label(L10n.vaultStatusNoVaultTitle, systemImage: "lock")
            } description: {
                Text(L10n.vaultSetupOpenKeyguardHint)
            }
        }
    }

    private var unlockedContent: some View {
        VStack(spacing: 0) {
            searchField
            Divider()
            twoPane
            if !snapshot.actions.isEmpty {
                Divider()
                actionStrip
            }
        }
    }

    // MARK: - Search field

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .font(.title3)
            TextField(L10n.vaultMainSearchPlaceholder, text: $query)
                .textFieldStyle(.plain)
                .font(.title3)
                .focused($searchFocused)
                .onSubmit { performEnter() }
                .bridgedText(
                    $query,
                    remote: snapshot.query,
                    remoteRevision: snapshot.queryRevision,
                    send: quickSearchModel.setQuickSearchQuery
                )
            shortcut("CopyPrimary", "c", .command)
            shortcut("CopySecret", "c", [.command, .shift])
            shortcut("CopyOtp", "c", [.command, .option])
            shortcut("OpenInBrowser", "f", [.command, .shift])
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .onKeyPress(.downArrow) {
            quickSearchModel.moveQuickSearchSelection(1); return .handled
        }
        .onKeyPress(.upArrow) {
            quickSearchModel.moveQuickSearchSelection(-1); return .handled
        }
        .onKeyPress(.tab, phases: .down) { press in
            quickSearchModel.moveQuickSearchActionSelection(press.modifiers.contains(.shift) ? -1 : 1)
            return .handled
        }
        .onKeyPress(.escape) {
            if snapshot.query.isEmpty {
                onDismiss()
            } else {
                // A programmatic clear: the command path bumps the query
                // revision so the local typing buffer adopts it.
                quickSearchModel.clearQuickSearchQuery()
            }
            return .handled
        }
    }

    private func shortcut(_ type: String, _ key: KeyEquivalent, _ modifiers: EventModifiers) -> some View {
        Button("") {
            quickSearchModel.invokeQuickSearchAction(type: type)
            onDismiss()
        }
        .keyboardShortcut(key, modifiers: modifiers)
        .opacity(0)
        .frame(width: 0, height: 0)
    }

    // MARK: - Two-pane (results | detail)

    private var twoPane: some View {
        HStack(spacing: 0) {
            resultsPane
                .frame(width: 300)
            Divider()
            detailPane
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var resultsPane: some View {
        if items.isEmpty {
            if snapshot.query.isEmpty {
                ContentUnavailableView {
                    Label(L10n.vaultMainSearchPlaceholder, systemImage: "magnifyingglass")
                } description: {
                    Text(L10n.quickSearchEmptyHint)
                }
            } else {
                ContentUnavailableView.search(text: snapshot.query)
            }
        } else {
            resultsList
        }
    }

    /// Rows arrive on the background-delivered `observeQuickSearchListDelta` channel;
    /// selection, detail, actions and TOTP stay on their own channels.
    @ViewBuilder
    private var resultsList: some View {
        if let listModel = quickSearchModel.quickSearchListModel {
            VaultListTableView(
                model: listModel,
                selection: listSelection,
                config: listConfig,
                colorScheme: colorScheme
            )
        }
    }

    @ViewBuilder
    private var detailPane: some View {
        if let detail = snapshot.selectedDetail {
            // The detail snapshot carries no icon, so take it from the matching result row.
            let selectedItem = items.first { $0.id == snapshot.selectedItemId }
            QuickSearchDetail(
                detail: detail,
                iconUrl: selectedItem?.iconUrl,
                iconPlaceholder: selectedItem?.iconPlaceholder,
                // The detail snapshot carries only `hasOtp`, so take the live code from
                // the row-level TOTP channel.
                totp: snapshot.selectedItemId.flatMap { quickSearchModel.quickSearchListModel?.totpStates[$0] }
            ) { type in
                quickSearchModel.invokeQuickSearchAction(type: type)
                onDismiss()
            }
            .id(snapshot.selectedItemId)
        } else {
            ContentUnavailableView {
                Label(L10n.vaultViewNoItemSelectedTitle, systemImage: "sidebar.right")
            } description: {
                Text(L10n.quickSearchSelectResultHint)
            }
        }
    }

    // MARK: - Action strip

    private var actionStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(snapshot.actions, id: \.type) { action in
                    Button {
                        quickSearchModel.invokeQuickSearchAction(type: action.type)
                        onDismiss()
                    } label: {
                        HStack(spacing: 6) {
                            Text(action.title)
                            if let sc = action.shortcut {
                                Text(sc).font(.caption.monospaced()).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(action.selected ? .accentColor : nil)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }

    private func performEnter() {
        if let idx = snapshot.selectedActionIndex?.intValue,
            idx >= 0, Int(idx) < snapshot.actions.count
        {
            quickSearchModel.invokeQuickSearchAction(type: snapshot.actions[Int(idx)].type)
        } else {
            quickSearchModel.invokeQuickSearchDefaultAction()
        }
        onDismiss()
    }
}

/// Mirrors `MasterPasswordView(mode:.unlock)`, sized for the overlay.
private struct QuickSearchUnlock: View {
    @Environment(VaultSessionModel.self) private var authModel

    var body: some View {
        MasterPasswordForm(makeSession: authModel.makeUnlockSession) { snapshot, actions in
            QuickSearchUnlockContent(snapshot: snapshot, actions: actions)
        }
    }
}

private struct QuickSearchUnlockContent: View {
    let snapshot: MasterPasswordSnapshot
    let actions: MasterPasswordActions
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(QuickSearchModel.self) private var quickSearchModel
    @State private var password = ""
    @State private var pendingSubmission: String?
    @FocusState private var passwordFocused: Bool

    private var isBusy: Bool { snapshot.isLoading }
    private var canSubmit: Bool {
        snapshot.canSubmit && snapshot.password == password
    }

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.shield")
                .font(.system(size: 40))
                .foregroundStyle(.tint)

            VStack(spacing: 6) {
                Text(L10n.agentHistoryResponseVaultLocked)
                    .font(.title3.weight(.semibold))
                if let reason = authModel.unlockLockReason, !reason.isEmpty {
                    Text(reason)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }

            SecureField(L10n.setupFieldAppPasswordLabel, text: $password)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 280)
                .focused($passwordFocused)
                .onSubmit(submit)
                .onChange(of: password) { _, newValue in
                    if pendingSubmission != newValue { pendingSubmission = nil }
                    actions.setPassword(newValue)
                }

            if let error = snapshot.passwordError, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }

            // macOS buttons hug their titles (no full-width touch sizing); the
            // bordered-prominent Unlock reads as the default action.
            Button(action: submit) {
                if isBusy {
                    ProgressView().controlSize(.small)
                } else {
                    Text(L10n.unlockButtonUnlock)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.regular)
            .disabled(!canSubmit || isBusy)

            if authModel.unlockHasBiometric {
                Button {
                    authModel.triggerUnlockBiometric()
                } label: {
                    Label(L10n.unlockBiometricTouchIdTitle, systemImage: "touchid")
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .disabled(isBusy)
            }

            if authModel.unlockHasFido2 {
                Button(L10n.fido2UnlockTitle, systemImage: "key") { authModel.triggerUnlockFido2() }
                    .disabled(authModel.unlockIsLoading)
            }
            if authModel.unlockHasYubiKey {
                Button {
                    authModel.triggerUnlockYubiKey()
                } label: {
                    Label(L10n.unlockYubikeyTitle, systemImage: "key.fill")
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .disabled(isBusy)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
        .onAppear {
            authModel.setUnlockScreenVisible(true)
            passwordFocused = true
        }
        .onDisappear {
            pendingSubmission = nil
            authModel.setUnlockScreenVisible(false)
            // Don't retain the master password across hide.
            password = ""
        }
        .onChange(of: canSubmit) { _, enabled in
            if enabled, pendingSubmission == password {
                submit()
            }
        }
        // Re-assert focus on every panel show.
        .onChange(of: quickSearchModel.quickSearchFocusToken) { _, _ in passwordFocused = true }
    }

    private func submit() {
        guard !isBusy, !password.isEmpty else { return }
        actions.setPassword(password)
        guard canSubmit else {
            pendingSubmission = password
            return
        }
        pendingSubmission = nil
        actions.submit()
    }
}

/// Looks like the Vault detail (`CipherDetailView`), but stays on the quick-search
/// data and actions: it must not touch the detail channel that the main window owns.
private struct QuickSearchDetail: View {
    @Environment(QuickSearchModel.self) private var quickSearchModel
    let detail: QuickSearchDetailSnapshot
    let iconUrl: String?
    let iconPlaceholder: String?
    /// nil until the first per-second push arrives.
    let totp: TotpFieldSnapshot?
    let invoke: (String) -> Void
    @State private var revealSecret = false

    var body: some View {
        Form {
            Section {
                if let value = detail.primaryValue, !value.isEmpty {
                    field(
                        label: Self.label(detail.primaryType) ?? L10n.username,
                        value: value,
                        colorize: detail.primaryType == "PUBLIC_KEY",
                        actionType: "CopyPrimary"
                    )
                }
                if let value = detail.secretValue, !value.isEmpty {
                    field(
                        label: Self.label(detail.secretType) ?? L10n.password,
                        value: value,
                        monospace: true,
                        concealed: !revealSecret,
                        colorize: detail.secretType == "PASSWORD" || detail.secretType == "PRIVATE_KEY",
                        actionType: "CopySecret",
                        reveal: { revealSecret.toggle() },
                        revealed: revealSecret
                    )
                }
                if detail.hasOtp {
                    otpField
                }
                if let url = detail.launchUrl, !url.isEmpty {
                    field(label: L10n.website, value: url, actionType: "OpenInBrowser")
                }
            }
        }
        .formStyle(.grouped)
        // The panel keeps its hosting view and selection while hidden. Revealing
        // a secret should last only for the current presentation.
        .onChange(of: quickSearchModel.quickSearchVisible) { _, visible in
            if !visible { revealSecret = false }
        }
        // Also reset on presentation in case the hidden hosting view deferred
        // updates and coalesced the visibility changes.
        .onChange(of: quickSearchModel.quickSearchFocusToken) { _, _ in
            revealSecret = false
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            DetailHeaderBar(title: detail.title) {
                FaviconView(
                    url: iconUrl,
                    placeholder: iconPlaceholder,
                    fallbackSymbol: "key.fill",
                    size: 36
                )
            } trailing: {
                // The quick-search producer exposes no favourite / history actions.
            }
        }
    }

    /// `actions: []` turns off the cell's built-in menu and copy control, so the
    /// `accessories` buttons own the affordances and route through `invoke`.
    @ViewBuilder
    private func field(
        label: String,
        value: String,
        monospace: Bool = false,
        concealed: Bool = false,
        colorize: Bool = false,
        actionType: String,
        reveal: (() -> Void)? = nil,
        revealed: Bool = false
    ) -> some View {
        FieldCell(
            title: label, actions: [], invoke: { _ in }, layout: .stacked,
            accessibilityValueOverride: concealed ? L10n.hidden : value
        ) {
            AnimatedConcealedText(
                text: value, masked: concealed, monospace: monospace, lineLimit: 2, colorize: colorize
            )
        } accessories: {
            if let reveal {
                Button {
                    reveal()
                } label: {
                    Image(systemName: revealed ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .help(revealed ? L10n.hide : L10n.fileActionRevealTitle)
                .accessibilityLabel(revealed ? L10n.hide : L10n.fileActionRevealTitle)
            }
            Button {
                invoke(actionType)
            } label: {
                Image(systemName: actionType == "OpenInBrowser" ? "arrow.up.right.square" : "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .help(actionType == "OpenInBrowser" ? L10n.openAction : L10n.copy)
            .accessibilityLabel(actionType == "OpenInBrowser" ? L10n.openAction : L10n.copy)
        }
    }

    @ViewBuilder
    private var otpField: some View {
        FieldCell(
            title: L10n.oneTimePassword, actions: [], invoke: { _ in }, layout: .stacked,
            accessibilityValueOverride: totp.map { $0.isError ? L10n.errorInvalidKey : $0.codeRaw }
        ) {
            if let totp {
                TotpBadgeView(totp: totp)
            } else {
                ProgressView().controlSize(.small)
            }
        } accessories: {
            Button {
                invoke("CopyOtp")
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .help(L10n.copyOtpCode)
            .accessibilityLabel(L10n.copyOtpCode)
        }
    }

    private static func label(_ type: String?) -> String? {
        switch type {
        case "USERNAME": return L10n.username
        case "EMAIL": return L10n.email
        case "PHONE_NUMBER": return L10n.phone
        case "CARD_NUMBER": return L10n.cardNumber
        case "CARD_CVV": return L10n.cardCvvShortLabel
        case "PASSWORD": return L10n.password
        case "PUBLIC_KEY": return L10n.publicKey
        case "PRIVATE_KEY": return L10n.privateKey
        case "VALUE": return L10n.fieldValue
        default: return nil
        }
    }
}

#endif
