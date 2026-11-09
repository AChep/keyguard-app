import SwiftUI
import KeyguardShared

/// Pairs this app with the app of another device, in the way the user picked, as this
/// device of the linked devices screen; which of the two admits the other one is not the
/// user's choice. Once linked it shows so, until the user is done or pairs one more
/// device. Leaving the screen stops the pairing.
struct KeyguardPairView: View {
    @State private var model: KeyguardPairingModel
    private let title: String
    /// Shown while linking needs Premium.
    private let premiumNote: String
    /// Pairs one more device, once the devices are linked.
    private let addAnotherTitle: String
    /// Runs once the user is done, or the vault locks.
    let onComplete: () -> Void

    init(
        makeSource: @escaping () -> any KeyguardPairingSource,
        title: String,
        premiumNote: String,
        addAnotherTitle: String,
        onComplete: @escaping () -> Void
    ) {
        _model = State(wrappedValue: KeyguardPairingModel(makeSource: makeSource))
        self.title = title
        self.premiumNote = premiumNote
        self.addAnotherTitle = addAnotherTitle
        self.onComplete = onComplete
    }

    private var snapshot: KeyguardPairingSnapshot { model.snapshot }

    var body: some View {
        Group {
            if snapshot.loaded {
                ScrollView {
                    VStack(spacing: 24) {
                        KeyguardPairingFlowView(model: model) {
                            if snapshot.premiumRequired {
                                KeyguardPremiumNote(text: premiumNote)
                                    .font(.callout)
                            }
                        } doneActions: {
                            HStack(spacing: 12) {
                                if snapshot.canAddAnother {
                                    Button(action: model.addAnother) {
                                        Text(addAnotherTitle)
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.bordered)
                                }
                                Button(action: onComplete) {
                                    Text(L10n.done)
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                            }
                            .controlSize(.large)
                        }
                        // The name and the relay belong to this device, so the
                        // linked devices screen sets them, not each pairing.
                        Text(snapshot.pairingAsText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: 480)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                    .frame(maxWidth: .infinity)
                }
            } else {
                LoadingIndicator()
            }
        }
        .navigationTitle(title)
        .observing(start: model.start, stop: model.stop)
        // The vault locked.
        .onChange(of: model.didComplete) { _, completed in
            if completed { onComplete() }
        }
    }
}

/// Links a device: a menu asks which of the two devices shows the code, so the pairing
/// starts right away. Dimmed while disabled.
struct KeyguardPairMenuRow: View {
    let title: String
    let text: String
    let systemImage: String
    /// Pairs, scanning the code of the other device if `true`, or showing one.
    let pair: (_ scan: Bool) -> Void

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Menu {
            KeyguardPairMethodButtons(pair: pair)
        } label: {
            DetailRowLabel(
                systemImage: systemImage, title: title, subtitle: text, disclosure: "chevron.up.chevron.down"
            )
            .touchTarget()
            .contentShape(Rectangle())
            .opacity(isEnabled ? 1 : 0.5)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
    }
}

/// The items of a menu that pairs a device: this device scans the code of the other one,
/// or shows its own.
struct KeyguardPairMethodButtons: View {
    /// Pairs, scanning the code of the other device if `true`, or showing one.
    let pair: @MainActor (_ scan: Bool) -> Void

    var body: some View {
        Button {
            pair(true)
        } label: {
            Label {
                Text(L10n.appLinkPairingMethodScanTitle)
                Text(L10n.appLinkPairingMethodScanText)
            } icon: {
                Image(systemName: "qrcode.viewfinder")
            }
        }
        Button {
            pair(false)
        } label: {
            Label {
                Text(L10n.appLinkPairingMethodShowTitle)
                Text(L10n.appLinkPairingMethodShowText)
            } icon: {
                Image(systemName: "qrcode")
            }
        }
    }
}

/// Says that linking needs Premium.
struct KeyguardPremiumNote: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "star.circle")
            .foregroundStyle(.orange)
    }
}
