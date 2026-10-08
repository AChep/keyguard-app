import SwiftUI
import KeyguardShared

struct ValueFieldCell: View {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void
    // Only used when the producer registers no reveal handler (`revealActionId == nil`).
    @State private var localRevealed = false

    /// `true` when the producer drives reveal (and so the reprompt gate).
    private var producerGated: Bool { item.revealActionId != nil }

    private var revealed: Bool {
        if item.revealLocked { return false }
        return producerGated ? item.isVisible : localRevealed
    }

    /// What VoiceOver should speak as the field value: "Hidden" while a concealed
    /// field is masked (so the dotted mask string is never read aloud), otherwise
    /// the plaintext (nil lets the combined element speak the visible value).
    private var accessibilityValue: String? {
        guard item.concealed else { return item.text }
        return revealed ? item.text : L10n.hidden
    }

    var body: some View {
        FieldCell(
            title: item.title,
            actions: item.actions,
            invoke: invoke,
            accessibilityValueOverride: accessibilityValue
        ) {
            HStack(spacing: 8) {
                // The username row's Gravatar (the shared "Load Gravatar icons"
                // preference); absent for every other value row.
                if let avatar = item.avatarUrl, let url = URL(string: avatar) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Image(systemName: "person.crop.circle")
                            .resizable()
                            .scaledToFit()
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 20, height: 20)
                    .clipShape(Circle())
                    .accessibilityHidden(true)
                }
                AnimatedConcealedText(
                    // Gated + still concealed: the producer sends no plaintext, so
                    // this is empty until a successful (possibly reprompt-gated)
                    // reveal. Without a reveal handler the value is always present.
                    text: item.text ?? "",
                    masked: item.concealed && !revealed,
                    monospace: item.monospace,
                    colorize: item.colorize
                )
            }
        } accessories: {
            // A policy-locked field (org "hide passwords") is never revealable, so it
            // gets no eye toggle at all — matching Compose, and the plaintext is never
            // even sent to this view.
            if item.concealed && !item.revealLocked {
                DetailIconButton(
                    title: revealed ? L10n.hide : L10n.fileActionRevealTitle,
                    systemImage: revealed ? "eye.slash" : "eye"
                ) {
                    if let revealActionId = item.revealActionId {
                        // Request reveal / hide through the producer; never flips a
                        // local state over already-decrypted text.
                        invoke(revealActionId)
                    } else {
                        localRevealed.toggle()
                    }
                }
            }
        }
    }
}
