#if os(macOS)
import SwiftUI
import AppKit

/// Setup instructions use the same resolved socket as the platform runtime,
/// including builds without access to the App Group container.
struct SshAgentSetupView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel

    private var socketPath: String { sshAgentModel.sshAgentStatus.sshAuthSock ?? "" }
    private var envCommand: String {
        let quotedPath = socketPath.replacingOccurrences(of: "'", with: "'\"'\"'")
        return "export SSH_AUTH_SOCK='\(quotedPath)'"
    }
    private var configSnippet: String {
        let quotedPath =
            socketPath
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "Host *\n  IdentityAgent \"\(quotedPath)\""
    }
    private static let verifyListCommand = "ssh-add -L"
    private static let verifyConnectCommand = "ssh -T git@github.com"

    private enum SetupOption: String, CaseIterable, Identifiable {
        case env
        case config

        var id: String { rawValue }

        var title: String {
            switch self {
            case .env: return L10n.sshAgentSetupEnvironmentVariableTitle
            case .config: return L10n.sshAgentSetupSshConfigTitle
            }
        }
    }

    @State private var selectedOption: SetupOption = .env

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(L10n.sshAgentSetupIntroText)
                    .foregroundStyle(.secondary)

                // Use the shared step-1 title to match steps 2/3 and the common
                // SshAgentSetupScreen; appleEnableTheSshAgent stays as body copy.
                stepHeader(L10n.sshAgentSetupStep1Title)
                Text(L10n.sshAgentSetupEnableStepText)
                codeBlock(socketPath)

                stepHeader(L10n.sshAgentSetupStep2Title)
                Picker(L10n.sshAgentSetupStep2Title, selection: $selectedOption) {
                    ForEach(SetupOption.allCases) { option in
                        Text(option.title).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                switch selectedOption {
                case .env:
                    Text(L10n.sshAgentSetupShellProfileHint)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    codeBlock(envCommand)
                case .config:
                    Text(L10n.sshAgentSetupSshConfigHint)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    codeBlock(configSnippet, file: "~/.ssh/config")
                }

                stepHeader(L10n.sshAgentSetupStep3Title)
                Text(L10n.sshAgentSetupTestConnectionHint)
                codeBlock(Self.verifyListCommand)
                codeBlock(Self.verifyConnectCommand)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
    }

    @ViewBuilder
    private func stepHeader(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .padding(.top, 8)
    }

    @ViewBuilder
    private func codeBlock(_ text: String, file: String? = nil) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 6) {
                if let file {
                    Text(file)
                        .font(.caption.monospaced())
                        .foregroundStyle(.tertiary)
                }
                Text(text)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            CopyButton(text: text)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(nsColor: .quaternarySystemFill))
        )
    }
}

/// Copy-to-pasteboard button with transient confirmation: the icon swaps to a
/// checkmark and the tooltip/label change to "Copied" for ~1.5s after a tap, so
/// the user gets feedback that the snippet was copied. Carries an explicit
/// accessibilityLabel since `.help()` is only a hover tooltip.
private struct CopyButton: View {
    let text: String

    @State private var copied = false
    @State private var revertTask: Task<Void, Never>?

    var body: some View {
        Button {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            withAnimation(.easeInOut(duration: 0.15)) {
                copied = true
            }
            revertTask?.cancel()
            revertTask = Task {
                try? await Task.sleep(for: .milliseconds(1500))
                if !Task.isCancelled {
                    await MainActor.run {
                        withAnimation(.easeInOut(duration: 0.15)) {
                            copied = false
                        }
                    }
                }
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                .foregroundStyle(copied ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
        }
        .buttonStyle(.borderless)
        .help(copied ? L10n.copiedValue : L10n.copy)
        .accessibilityLabel(copied ? L10n.copiedValue : L10n.copy)
        .accessibilityInputLabels([L10n.copy])
        .onDisappear { revertTask?.cancel() }
    }
}

#endif
