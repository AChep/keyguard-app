import SwiftUI
import KeyguardShared

struct CardFieldCell: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void
    @State private var localRevealed = false

    private var producerGated: Bool { item.revealActionId != nil }
    private var revealed: Bool {
        if item.revealLocked { return false }
        return producerGated ? item.isVisible : localRevealed
    }

    /// Native menus do not consistently include every label child in their
    /// accessibility value. Include the cardholder explicitly, while preserving
    /// the number's concealment and authentication gate.
    private var accessibilityValue: String? {
        let number =
            item.concealed && !revealed
            ? L10n.hidden
            : item.cardNumberFormatted
        return [number, item.text]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }

    var body: some View {
        FieldCell(
            title: nil,
            actions: item.actions,
            invoke: invoke,
            accessibilityValueOverride: accessibilityValue
        ) {
            VStack(alignment: .leading, spacing: 4) {
                if let brand = item.cardBrand, !brand.isEmpty {
                    Text(brand)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                numberContent
                if let cardholder = item.text, !cardholder.isEmpty {
                    Text(cardholder)
                        .font(.subheadline)
                        .textSelection(.enabled)
                }
            }
            .foregroundStyle(.primary)
            .fixedSize(horizontal: false, vertical: true)
        } accessories: {
            // Only a concealed, non-empty, non-policy-locked number gets a reveal
            // toggle (matching the Compose item, which hides the toggle when the
            // number is null or the org "hide passwords" policy forbids it).
            if item.concealed && !item.revealLocked {
                DetailIconButton(
                    title: revealed ? L10n.hide : L10n.fileActionRevealTitle,
                    systemImage: revealed ? "eye.slash" : "eye"
                ) {
                    if let revealActionId = item.revealActionId {
                        invoke(revealActionId)
                    } else {
                        localRevealed.toggle()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var numberContent: some View {
        // When concealed + not yet revealed, render the obscured string. In the
        // producer-gated mode the formatted (plaintext) number is withheld until the
        // reveal succeeds; in the legacy mode both strings are present and we pick.
        if item.concealed && !revealed {
            Text(item.cardNumberObscured ?? "")
                .font(.body.monospaced())
                .textSelection(.enabled)
        } else if let formatted = item.cardNumberFormatted, !formatted.isEmpty {
            Text(formatted)
                .font(.body.monospaced())
                .textSelection(.enabled)
        } else {
            // No number: the empty-card placeholder, mirroring the Compose
            // `card_number_empty_label` row with its credit-card-off icon.
            HStack(spacing: 8) {
                Image(systemName: "creditcard.trianglebadge.exclamationmark")
                    .foregroundStyle(.secondary)
                Text(L10n.cardNumberEmptyLabel)
                    .font(.body.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
    }
}
