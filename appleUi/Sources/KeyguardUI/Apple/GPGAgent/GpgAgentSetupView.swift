#if os(macOS)
import SwiftUI

struct GpgAgentSetupView: View {
    @Environment(GpgAgentModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(L10n.gpgAgentSetupIntro).foregroundStyle(.secondary)

                step(L10n.gpgAgentSetupStep1Title)
                Text(L10n.gpgAgentSetupStep1Text)

                step(L10n.gpgAgentSetupStep2Title)
                Text(L10n.gpgAgentSetupMacosStep2Text)
                if let command = model.gpgAgentStatus.setupCommand, model.gpgAgentStatus.running {
                    GpgAgentCommandBlock(text: command)
                } else {
                    Label(L10n.gpgAgentSetupStep1Title, systemImage: "info.circle")
                        .foregroundStyle(.secondary)
                }
                GpgAgentCommandBlock(text: "export GNUPGHOME=\"$HOME/.keyguard/gnupg\"")

                step(L10n.gpgAgentSetupStep3Title)
                Text(L10n.gpgAgentSetupStep3Text)
                GpgAgentCommandBlock(text: "gpg --no-autostart --import /path/to/keyguard-public-key.asc")

                step(L10n.gpgAgentSetupStep4Title)
                Text(L10n.gpgAgentSetupStep4Text)
                GpgAgentCommandBlock(text: "gpg-connect-agent --no-autostart 'KEYINFO --list' /bye")
                GpgAgentCommandBlock(
                    text: """
                        printf '%s\\n' 'Keyguard GPG agent test' | \\
                          gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --armor --clearsign
                        """)
                GpgAgentCommandBlock(text: "gpg --no-autostart --decrypt /path/to/message.asc")

                step(L10n.gpgAgentSetupStep5Title)
                Text(L10n.gpgAgentSetupStep5Text)
                GpgAgentCommandBlock(
                    text: """
                        git config --local gpg.format openpgp
                        git config --local gpg.program gpg
                        git config --local user.signingkey YOUR_KEY_FINGERPRINT
                        git config --local commit.gpgsign true
                        """)
                GpgAgentCommandBlock(text: "GNUPGHOME=\"$HOME/.keyguard/gnupg\" git commit -S")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
    }

    private func step(_ title: String) -> some View {
        Text(title).font(.subheadline.weight(.semibold)).padding(.top, 8)
    }
}
#endif
