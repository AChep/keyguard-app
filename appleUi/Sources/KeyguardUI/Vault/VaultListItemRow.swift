import SwiftUI
import KeyguardShared

// A namespace of SwiftUI builders, so the whole thing belongs on the main actor;
// without this its static funcs are nonisolated and every view they touch crosses.
@MainActor
enum VaultRowInline {

    // MARK: - Item row

    /// The rich cipher-item row. `row == nil` renders the placeholder skeleton.
    @ViewBuilder
    static func itemRow(
        row: VaultRow?,
        decoration: VaultRowDecoration?,
        colorScheme: ColorScheme,
        totpProvider: @escaping @MainActor @Sendable () -> TotpFieldSnapshot?,
        onCopyOtp: @escaping @MainActor @Sendable () -> Void,
        onBadgeTap: @escaping @MainActor @Sendable (VaultRowBadge) -> Void
    ) -> some View {
        if let row {
            itemContent(
                row,
                decoration: decoration,
                colorScheme: colorScheme,
                totpProvider: totpProvider,
                onCopyOtp: onCopyOtp,
                onBadgeTap: onBadgeTap
            )
        } else {
            placeholderRow
        }
    }

    private static func itemContent(
        _ row: VaultRow,
        decoration: VaultRowDecoration?,
        colorScheme: ColorScheme,
        totpProvider: @escaping @MainActor @Sendable () -> TotpFieldSnapshot?,
        onCopyOtp: @escaping @MainActor @Sendable () -> Void,
        onBadgeTap: @escaping @MainActor @Sendable (VaultRowBadge) -> Void
    ) -> some View {
        // Center the leading icon against the content column so a single-line
        // (or title + subtitle) row reads as vertically centred next to the icon.
        HStack(alignment: .center, spacing: 10) {
            // The multi-selection affordance — shown ONLY when the row carries the
            // Duplicates selection flags; the main list never sets them.
            if row.flags.contains(.selecting) {
                let selected = row.flags.contains(.selected)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .imageScale(.large)
                    .frame(width: 24)
                    .accessibilityLabel(L10n.select)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
            icon(row, colorScheme: colorScheme)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(highlightedTitle(row, decoration: decoration))
                            .lineLimit(1)
                        if let subtitle = row.subtitle {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(row.flags.contains(.multiline) ? 4 : 2)
                        }
                    }
                    Spacer(minLength: 8)
                    trailing(row, colorScheme: colorScheme)
                }
                if row.flags.contains(.hasTotp) || !row.badges.isEmpty {
                    FlowLayout(spacing: 6, clampsToWidth: true) {
                        if row.flags.contains(.hasTotp) {
                            // Isolated leaf so the per-second code/countdown
                            // update re-bodies only this cell, not the row.
                            TotpBadgeCell(provider: totpProvider, onCopyOtp: onCopyOtp)
                        }
                        ForEach(row.badges) { badge in
                            badgeView(badge, onBadgeTap: onBadgeTap)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                // The matched-field context badge, shown while a search is active.
                if let context = decoration?.contextBadgeText {
                    HStack(spacing: 4) {
                        if let symbol = decoration?.contextBadgeSymbol {
                            Image(systemName: symbol)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(context)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - Section / marker rows

    static func sectionRow(title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
    }

    static func markerRow(title: String) -> some View {
        Text(title)
            .font(.callout)
            .foregroundStyle(.secondary)
    }

    static func buttonRow(title: String, onTap: @escaping @MainActor @Sendable () -> Void) -> some View {
        Button(action: onTap) {
            Text(title)
                .font(.callout.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .buttonStyle(.borderedProminent)
        .touchTarget()
    }

    // MARK: - Title highlight

    private static func highlightedTitle(_ row: VaultRow, decoration: VaultRowDecoration?) -> AttributedString {
        highlightedText(row.title, utf16Ranges: decoration?.titleRanges ?? [])
    }

    // MARK: - Leading icon

    @ViewBuilder
    private static func icon(_ row: VaultRow, colorScheme: ColorScheme) -> some View {
        FaviconView(
            url: row.iconUrl,
            placeholder: row.iconInitials,
            fallbackSymbol: row.typeSymbol ?? "key",
            size: 30,
            accent: vaultAccentColor(
                colorScheme == .dark ? row.accentDarkArgb : row.accentLightArgb
            )
        )
        .overlay(alignment: .bottomTrailing) { iconBadges(row) }
    }

    @ViewBuilder
    private static func iconBadges(_ row: VaultRow) -> some View {
        // Each status glyph carries an SF Symbol *and* a VoiceOver label, so
        // the meaning is not conveyed by icon/colour alone.
        let glyphs: [(name: String, label: String)] = {
            var g: [(String, String)] = []
            if row.flags.contains(.favourite) { g.append(("star.fill", L10n.homeFavoritesLabel)) }
            if row.flags.contains(.reprompt) { g.append(("lock.fill", L10n.filterAuthRepromptItems)) }
            if row.flags.contains(.attachments) { g.append(("paperclip", L10n.attachments)) }
            return g
        }()
        if !glyphs.isEmpty {
            HStack(spacing: 1) {
                ForEach(glyphs, id: \.name) { glyph in
                    Image(systemName: glyph.name)
                        .font(.caption2.weight(.bold))
                        .imageScale(.small)
                        .foregroundStyle(glyph.name == "star.fill" ? Color.yellow : Color.secondary)
                        .accessibilityLabel(glyph.label)
                }
            }
            .padding(2)
            .background(.background, in: Capsule())
            .overlay(Capsule().strokeBorder(.quaternary, lineWidth: 0.5))
            .offset(x: 3, y: 3)
        }
    }

    // MARK: - Badges

    private static func badgeView(
        _ badge: VaultRowBadge, onBadgeTap: @escaping @MainActor @Sendable (VaultRowBadge) -> Void
    ) -> some View {
        Button {
            onBadgeTap(badge)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: badge.kind.symbol)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(badge.text)
                    .font(.caption)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let text2 = badge.text2 {
                    Text(text2)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        // Keep the short subtext (e.g. file size) intact and
                        // let the long title truncate instead.
                        .layoutPriority(1)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(.quaternary, in: Capsule())
            // Keep the capsule compact while its entire 44pt label accepts taps.
            .touchTarget()
        }
        .buttonStyle(.plain)
    }

    // MARK: - Trailing accessories

    @ViewBuilder
    private static func trailing(_ row: VaultRow, colorScheme: ColorScheme) -> some View {
        HStack(spacing: 6) {
            if let name = row.orgName {
                orgChip(name, row: row, colorScheme: colorScheme)
            }
            if row.flags.contains(.error) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
                    // .help() is a macOS-only pointer tooltip; pair it with an
                    // accessibilityLabel so VoiceOver also announces the failure.
                    .help(L10n.vaultItemSyncFailedText)
                    .accessibilityLabel(L10n.vaultItemSyncFailedText)
            }
        }
    }

    private static func orgChip(_ name: String, row: VaultRow, colorScheme: ColorScheme) -> some View {
        let accent =
            vaultAccentColor(
                colorScheme == .dark ? row.orgAccentDarkArgb : row.orgAccentLightArgb
            ) ?? .secondary
        return Text(name)
            .font(.caption2)
            .lineLimit(1)
            .frame(maxWidth: 90)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(accent, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            .foregroundStyle(accent.contrastingTextColor)
    }

    // MARK: - Placeholder

    /// The `row == nil` skeleton: same geometry as an item row (30pt icon +
    /// title / subtitle bars) so late content lands without a layout jump.
    private static var placeholderRow: some View {
        HStack(alignment: .center, spacing: 10) {
            RoundedRectangle(cornerRadius: 30 * 0.22, style: .continuous)
                .fill(.quaternary)
                .frame(width: 30, height: 30)
            VStack(alignment: .leading, spacing: 4) {
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(.quaternary)
                    .frame(width: 140, height: 12)
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(.quinary)
                    .frame(width: 90, height: 9)
            }
            Spacer(minLength: 8)
        }
        .padding(.vertical, 2)
        .accessibilityHidden(true)
    }
}

// MARK: - Row host

struct VaultRowHost: View {
    let store: VaultRowStore
    let entry: VaultRowEntry
    let model: any VaultRowListModel
    /// Gates the context menu and the quick filters, and carries the highlighted
    /// `selectedRowId`.
    let config: VaultListConfig
    let colorScheme: ColorScheme
    /// The iOS edit-mode gate: while editing, an active multi-selection swaps the
    /// row's context menu to the bulk actions. Always `false` on macOS.
    var editing: Bool = false
    /// UIKit supplies native cell margins; macOS retains its compact row inset.
    var horizontalPadding: CGFloat = 8

    var body: some View {
        let box = store.box(for: entry.id)
        let row = box.row
        let decoration = box.decoration
        content(row: row, decoration: decoration)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, 2)
            .accessibilityAddTraits(isRowSelected ? .isSelected : [])
            #if os(macOS)
        .background(alignment: .center) {
            if isRowSelected {
                RoundedRectangle(cornerRadius: 8)
                .fill(Color.accentColor.opacity(0.2))
                .padding(.horizontal, 4)
            }
        }
            #endif
    }

    private var isRowSelected: Bool {
        entry.kind == .item && config.selectedRowId != nil && config.selectedRowId == entry.id
    }

    @ViewBuilder
    private func content(row: VaultRow?, decoration: VaultRowDecoration?) -> some View {
        switch entry.kind {
        case .item:
            VaultRowInline.itemRow(
                row: row,
                decoration: decoration,
                colorScheme: colorScheme,
                totpProvider: { model.totpStates[entry.id] },
                onCopyOtp: { model.performVaultRowAction(rowId: entry.id, actionId: VaultActions.copyOtp) },
                onBadgeTap: { model.performVaultBadgeTap(rowId: entry.id, badgeId: $0.id) }
            )
            .modifier(
                VaultRowContextMenu(
                    model: model,
                    rowId: entry.id,
                    rowRevision: row?.rev,
                    editing: editing,
                    enabled: config.contextMenu
                ))
        case .button:
            // The row's own id doubles as the action id dispatched through
            // `performVaultRowAction`.
            VaultRowInline.buttonRow(title: row?.title ?? "") {
                model.performVaultRowAction(rowId: entry.id, actionId: entry.id)
            }
        case .quickFilters:
            // The Kotlin side always emits this marker; the client hides the
            // quick-filter chips when the surface disables them or a query is active.
            if config.quickFilters && !model.isQueryActive {
                VaultQuickFilterChips(
                    chips: model.quickFilterChips,
                    state: model.filterState,
                    invoke: { model.invokeFilter(id: $0) }
                )
            }
        case .section:
            VaultRowInline.sectionRow(title: row?.title ?? "")
        case .noItems:
            // Marker payloads carry only their kind; like the Compose list,
            // the native renderer supplies their localized placeholder text.
            VaultRowInline.markerRow(title: L10n.itemsEmptyLabel)
        case .noSuggestions:
            VaultRowInline.markerRow(title: L10n.vaultMainNoSuggestedItems)
        }
    }
}

private extension VaultRowBadge.Kind {
    /// The badge's SF Symbol; the descriptor carries no symbol by design.
    var symbol: String {
        switch self {
        case .password: return "key"
        case .passkey: return "person.badge.key"
        case .attachment: return "paperclip"
        }
    }
}

/// Decodes a packed ARGB accent (`0` = no accent) into a SwiftUI `Color`.
func vaultAccentColor(_ argb: Int32) -> Color? {
    guard argb != 0 else { return nil }
    return Color(argb: UInt32(bitPattern: argb))
}

private struct TotpBadgeCell: View {
    let provider: @MainActor @Sendable () -> TotpFieldSnapshot?
    let onCopyOtp: @MainActor @Sendable () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // `provider()` reads the session's live TOTP map; evaluating it inside
        // this body is what scopes the per-second invalidation to this cell.
        let totp = provider() ?? TotpFieldSnapshot.companion.loading
        Button(action: onCopyOtp) {
            badge(totp)
                #if os(iOS)
            // Keep the code next to the subtitle while reserving room below
            // it for taps, rather than centering it in a padded badge row.
            .frame(minWidth: 44, minHeight: 44, alignment: .topLeading)
            .contentShape(Rectangle())
                #endif
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L10n.copyOtpCode)
        .accessibilityValue(totp.isError ? L10n.errorInvalidKey : totp.codeRaw)
    }

    @ViewBuilder
    private func badge(_ totp: TotpFieldSnapshot) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if totp.isError {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.red)
                Text(L10n.errorInvalidKey)
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
                    .lineLimit(1)
            } else {
                if totp.groups.isEmpty {
                    Text(verbatim: "••• •••")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(Color.secondary)
                } else {
                    Text(totp.groups.map { $0.joined() }.joined(separator: " "))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.tint)
                        .environment(\.layoutDirection, .leftToRight)
                        .lineLimit(1)
                        .contentTransition(reduceMotion ? .identity : .numericText())
                        .animation(reduceMotion ? nil : .snappy, value: totp.codeRaw)
                }
                if totp.isTimeBased, !totp.counterText.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Image(systemName: "clock")
                            .accessibilityHidden(true)
                        Group {
                            if let seconds = Int(totp.counterText) {
                                Text(Duration.seconds(seconds), format: .units(allowed: [.seconds], width: .narrow))
                            } else {
                                Text(totp.counterText)
                            }
                        }
                        .contentTransition(reduceMotion ? .identity : .numericText(countsDown: true))
                        .animation(reduceMotion ? nil : .default, value: totp.counterText)
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(Color.secondary)
                }
            }
        }
    }
}
