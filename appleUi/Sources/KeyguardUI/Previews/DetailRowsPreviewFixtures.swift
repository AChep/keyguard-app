#if DEBUG
import KeyguardShared

/// Synthetic data only. These fixtures exercise the production snapshot renderer.
enum DetailRowsPreviewFixtures {
    static func action(_ id: String, _ title: String, copy: Bool = false, icon: String? = nil) -> VaultActionSnapshot {
        VaultActionSnapshot(
            id: id, title: title, isCopy: copy, iconName: icon, startsSection: false, switchState: nil, danger: false)
    }

    static func item(
        _ id: String, _ kind: VaultItemKind = .value, title: String? = nil, text: String? = nil,
        actions: [VaultActionSnapshot] = [], click: String? = nil, concealed: Bool = false,
        visible: Bool = false, locked: Bool = false, reveal: String? = nil,
        totp: TotpFieldSnapshot? = nil, attachment: AttachmentFieldSnapshot? = nil,
        url: String? = nil, markdown: Bool = false, monospace: Bool = false,
        severity: VaultAlertSeverity? = nil, colorize: Bool = false, uriIcon: UriIconSnapshot? = nil
    ) -> VaultItemSnapshot {
        VaultItemSnapshot(
            id: id, kind: kind, title: title, text: text, concealed: concealed, monospace: monospace,
            totp: totp, launchUrl: url, switchValue: true, actions: actions, markdown: markdown,
            avatarUrl: nil, shapeState: -1, clickActionId: click,
            badges: kind == .quickBadges ? [VaultBadgeSnapshot(title: "Personal", text: "Synced")] : [],
            attachment: attachment, spacerHeight: 16, cardBrand: kind == .card ? "Visa" : nil,
            cardNumberFormatted: kind == .card && visible ? "4111 1111 1111 1111" : nil,
            cardNumberObscured: kind == .card ? "•••• •••• •••• 1111" : nil,
            isVisible: visible, revealActionId: reveal, revealLocked: locked, alertSeverity: severity,
            colorize: colorize, uriIcon: uriIcon
        )
    }

    static var linkedUris: [VaultItemSnapshot] {
        let actions = [
            action("copy-url", "Copy URL", copy: true),
            action("open-url", "Open in browser"),
            action("share-url", "Share"),
        ]
        return [
            item("uris", .section, title: "Linked URIs / apps"),
            item(
                "web", .uri, title: "https://example.com", actions: actions,
                uriIcon: UriIconSnapshot(kind: .website, url: nil)),
            item(
                "ios", .uri, title: "com.example.ios", actions: [action("copy-ios", "Copy bundle ID", copy: true)],
                uriIcon: UriIconSnapshot(kind: .app, url: nil)),
            item(
                "android", .uri, title: "com.example.android",
                actions: [action("copy-android", "Copy package name", copy: true)],
                uriIcon: UriIconSnapshot(kind: .app, url: nil)),
            item(
                "long-url", .uri, title: "https://example.com/" + String(repeating: "long-path/", count: 8),
                text: "Existing subtitle", actions: actions, uriIcon: UriIconSnapshot(kind: .website, url: nil)),
            item(
                "regex", .uri, title: "^https://example\\.com/[0-9]+$", actions: actions,
                colorize: true, uriIcon: UriIconSnapshot(kind: .link, url: nil)),
            item("no-actions", .uri, title: "custom:unavailable", uriIcon: UriIconSnapshot(kind: .link, url: nil)),
            item("credentials", .section, title: "Credentials"),
            item("passkey", .passkey, title: "demo@example.com", text: "example.com", click: "preview-passkey"),
            item("attachment", .attachment, title: "Recovery codes.txt", text: "2 KB", click: "preview-attachment"),
        ]
    }

    static func mixed(revealed: Bool, cardRevealed: Bool, tick: Int) -> [VaultItemSnapshot] {
        let seconds = 30 - tick % 30
        let code = tick / 30 % 2 == 0 ? "123456" : "654321"
        let totp = TotpFieldSnapshot(
            groups: [Array(code.prefix(3)).map(String.init), Array(code.suffix(3)).map(String.init)],
            codeRaw: code, isLoading: false, isError: false, isTimeBased: true,
            counterText: String(seconds), progress: Float(seconds) / 30
        )
        return [
            item(
                "username", title: "Username", text: "demo@example.com",
                actions: [action("copy-user", "Copy username", copy: true)]),
            item(
                "password", title: "Password", text: revealed ? "Synthetic-password-123!" : nil,
                actions: [action("copy-password", "Copy password", copy: true), action("large-type", "Large type")],
                concealed: true, visible: revealed, reveal: "reveal-password", colorize: true),
            item(
                "website", .uri, title: "https://example.com",
                actions: [action("copy-url", "Copy URL", copy: true)],
                uriIcon: UriIconSnapshot(kind: .website, url: nil)),
            item(
                "otp", .totp, title: "One-time password", actions: [action("copy-otp", "Copy code", copy: true)],
                totp: totp),
            item(
                "locked", title: "Policy-hidden password",
                actions: [action("copy-locked", "Copy password", copy: true)], concealed: true, locked: true,
                colorize: true),
            item("boolean", .toggle, title: "Verified", actions: [action("boolean-info", "More information")]),
            item("notes-section", .section, title: L10n.notes),
            item(
                "note", .note,
                text:
                    "A synthetic note with **Markdown**, a [link](https://example.com), and a list.\n\n- First line\n- Second line\n\n```text\nA long code line that scrolls horizontally without widening the form.\n```",
                markdown: true),
            item("credentials", .section, title: "Credentials"),
            item(
                "passkey", .passkey, title: "demo@example.com", text: "example.com",
                actions: [action("use-passkey", "Use")], click: "preview-passkey"),
            item(
                "card", .card, text: "Alex Example",
                actions: [action("copy-card", "Copy card number", copy: true), action("card-barcode", "Show barcode")],
                concealed: true, visible: cardRevealed, reveal: "reveal-card"),
            item(
                "identity", .identity, title: "Dr.", text: "Alex Example",
                actions: identityActions),
            item("files", .section, title: "Attachments"),
            item(
                "attachment", .attachment, title: "Recovery codes.txt", text: "2 KB",
                actions: [action("download", "Download"), action("remove-file", "Remove")], click: "preview-attachment"),
            item("related", .section, title: "Related items"),
            item(
                "linked", .action, title: "Another item", text: "Opens a pushed detail",
                actions: [action("push", "Another item")], click: "push"),
            item(
                "folder", .folder, actions: [action("parent", "Personal"), action("child", "Accounts")], click: "folder"
            ),
            item("organization", .organization, title: "Example organization", click: "organization"),
            item("collection", .collection, title: "Engineering", click: "collection"),
            item(
                "tags", .tags,
                actions: [
                    action("tag-work", "Work"), action("tag-travel", "Travel"),
                    action("tag-long", "A deliberately long tag that wraps on narrow screens"),
                ]),
            item("badges", .quickBadges),
            item(
                "actions", .quickActions,
                actions: [action("quick-one", "First action"), action("quick-two", "Second action")]),
            item("alerts", .section, title: "Status"),
            item(
                "alert", .alert, title: "Attention needed", text: "Synthetic supporting text.",
                actions: [action("retry", "Retry")]),
            item("action", .action, title: "Check password", actions: [action("check", "Check password")]),
            item("button", .button, text: "View history", actions: [action("history", "View history")]),
            item("info", .alert, title: "Information", text: "An informational row without an action."),
            item("qr-section", .section, title: "Wi-Fi"),
            item("qr", .qr, text: "WIFI:T:WPA;S:Example;P:synthetic-password;;"),
            item("spacer", .spacer),
            item("label", .label, text: "Saved to a synthetic account."),
            item("unsupported", .unsupported),
            item("empty-section", .section, title: "Empty section"),
        ]
    }

    static var longValues: [VaultItemSnapshot] {
        [
            item("long-label", title: "A deliberately long translated label for a field", text: "Short value"),
            item(
                "long-value", title: "Fingerprint", text: String(repeating: "0123456789ABCDEF", count: 8),
                actions: [action("copy-long", "Copy fingerprint", copy: true)], monospace: true),
            item(
                "long-url", .uri, title: "Website",
                text: "https://example.com/" + String(repeating: "long-path/", count: 12),
                actions: [action("copy-url", "Copy URL", copy: true)], url: "https://example.com"),
            item(
                "multiline", title: "Multiline value", text: "First line\nSecond line\nThird line",
                actions: [action("copy-multiline", "Copy", copy: true)]),
            item("unicode", title: "Обліковий запис", text: "開発用アカウント — حساب العمل"),
            item("untitled", text: "A value without a label"),
            item("empty", title: "Empty value", text: ""),
            item(
                "plain-note", .note,
                text: String(repeating: "Plain text notes remain readable at narrow widths. ", count: 10)),
        ]
    }

    static var securityNotices: [VaultItemSnapshot] {
        [
            item("password", title: "Password", text: "••••••••••••"),
            item(
                "reused", .alert, title: "Reused password", text: "Used by 3 items",
                actions: [action("show-reused", "Open")], severity: .error),
            item("username", title: "Username", text: "demo@example.com"),
            item(
                "inactive-totp", .alert, title: "Inactive one-time password",
                actions: [action("show-totp-service", "Open")], severity: .warning),
            item(
                "inactive-passkey", .alert, title: "Inactive passkey",
                actions: [action("show-passkey-service", "Open")], severity: .info),
            item("no-action-section", .section, title: "Without an action"),
            item("static-totp", .alert, title: "Inactive one-time password", severity: .warning),
        ]
    }

    static var identityActions: [VaultActionSnapshot] {
        [
            action("call", L10n.vaultViewCallPhoneAction, icon: "phone"),
            action("text", L10n.vaultViewTextPhoneAction, icon: "message"),
            action("email", L10n.vaultViewEmailAction, icon: "envelope"),
            action("directions", L10n.vaultViewNavigateAction, icon: "location.fill"),
        ]
    }

    static var identities: [VaultItemSnapshot] {
        [
            item("identity", .identity, text: "Alex Example", actions: identityActions),
            item("long-name-section", .section, title: "Long name and actions"),
            item(
                "long-name", .identity, title: "Dr.", text: "Alexandra Catherine Example-Smith",
                actions: [
                    action("long-directions", "Directions to the saved home address", icon: "location.fill"),
                    action("no-icon", "An action without an icon"),
                ]),
            item("no-actions-section", .section, title: "No actions"),
            item("no-actions", .identity, text: "Alex Example"),
        ]
    }

    static var supportingLabels: [VaultItemSnapshot] {
        [
            item("password", title: "Password", text: "••••••••••••"),
            item("password-revision", .label, text: "Password last modified on 9 September 2026 at 15:30"),
            item("website", .uri, title: "Website", text: "example.com"),
            item("tag-section", .section, title: "Tag"),
            item("tag", .tags, actions: [action("audit", "audit")]),
            item("metadata-spacer", .spacer),
            item("saved", .label, text: "Saved to AuditVault.kdbx"),
            item("created", .label, text: "Created at 9 September 2026 at 15:30"),
            item("modified", .label, text: "Last modified at 10 September 2026 at 21:28"),
        ]
    }

    static var attachmentStates: [VaultItemSnapshot] {
        let states: [AttachmentStatusKind] = [.none, .loading, .failed, .downloaded, .pendingUpload]
        return states.enumerated().map { index, state in
            item(
                "file-\(index)", .attachment, title: "Attachment \(index + 1).txt", text: "2 MB",
                actions: [action("file-action-\(index)", "File action")], click: "file-preview-\(index)",
                attachment: AttachmentFieldSnapshot(
                    status: state, progress: 0.5, downloadedText: "1 MB", autoResume: false))
        } + [
            item(
                "indeterminate", .attachment, title: "Starting download.txt",
                attachment: AttachmentFieldSnapshot(
                    status: .loading, progress: -1, downloadedText: nil, autoResume: false)),
            item(
                "auto-resume", .attachment, title: "Will resume.txt",
                attachment: AttachmentFieldSnapshot(status: .failed, progress: 0, downloadedText: nil, autoResume: true)
            ),
            item("otp-loading", .totp, title: "Loading code", totp: TotpFieldSnapshot.companion.loading),
            item("otp-error", .totp, title: "Invalid code", totp: TotpFieldSnapshot.companion.error),
        ]
    }
}
#endif
