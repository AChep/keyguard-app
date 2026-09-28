#if os(macOS)
import AppKit
import SwiftUI
import KeyguardShared

/// Unlocking only unlocks the vault. A separate click approves this operation.
struct GpgAgentApprovalView: View {
    @Environment(GpgAgentModel.self) private var gpgAgentModel
    @Environment(VaultSessionModel.self) private var authModel
    let request: GpgAgentRequestSnapshot
    @State private var decision = GpgAgentApprovalDecision()

    private var title: String {
        request.operation == .decrypt
            ? L10n.gpgAgentRequestApprovalDecryptTitle : L10n.gpgAgentRequestApprovalSignTitle
    }

    private var message: String {
        if request.operation == .decrypt {
            return request.callerName.isEmpty
                ? L10n.gpgAgentRequestApprovalDecryptMessageUnknownApp
                : L10n.gpgAgentRequestApprovalDecryptMessageKnownApp(request.callerName)
        }
        return request.callerName.isEmpty
            ? L10n.gpgAgentRequestApprovalSignMessageUnknownApp
            : L10n.gpgAgentRequestApprovalSignMessageKnownApp(request.callerName)
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { timeline in
            let remaining = max(0, Double(request.expiresAtEpochMs) / 1000 - timeline.date.timeIntervalSince1970)
            let countdown = L10n.agentApprovalExpiresInText(L10n.secondsPlural(Int(remaining.rounded(.up))))
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Label(title, systemImage: request.operation == .decrypt ? "lock.open.fill" : "signature")
                            .font(.headline)
                        Text(message)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        GroupBox {
                            VStack(alignment: .leading, spacing: 8) {
                                detailRow(
                                    L10n.encryptionKey,
                                    request.keyName.isEmpty ? L10n.agentApprovalUntitledKey : request.keyName)
                                if !request.keyFingerprint.isEmpty {
                                    detailRow(L10n.fingerprint, request.keyFingerprint)
                                }
                                detailRow(
                                    L10n.agentApprovalRequestedByLabel,
                                    request.callerName.isEmpty ? L10n.unknown : request.callerName)
                                if !request.callerPath.isEmpty {
                                    detailRow(L10n.agentApprovalPathLabel, request.callerPath)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(6)
                        }

                        if authModel.status == .locked {
                            MasterPasswordView(mode: .unlock)
                                .frame(height: 290)
                        } else if authModel.status != .unlocked {
                            ProgressView(L10n.vaultLoadingText)
                        }

                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
                }
                VStack(spacing: 16) {
                    HStack {
                        Spacer()
                        Button(L10n.ipcApprovalDeny, role: .cancel) { resolve(approved: false) }
                            .keyboardShortcut(.cancelAction)
                            .disabled(decision.isResolving)
                        // No default-action shortcut: Return in the unlock form must
                        // never approve a signature or decryption after unlocking.
                        Button(L10n.ipcApprovalApprove) { resolve(approved: true) }
                            .buttonStyle(.borderedProminent)
                            .disabled(decision.isResolving || remaining <= 0 || authModel.status != .unlocked)
                    }

                    ProgressView(value: remaining, total: max(1, Double(request.timeoutMs) / 1000)) {
                        Text(countdown).font(.caption).foregroundStyle(.secondary)
                    }
                    .progressViewStyle(.linear)
                    .accessibilityLabel(L10n.agentApprovalTimeLeftLabel)
                    .accessibilityValue(countdown)
                }
                .padding(20)
            }
        }
        .frame(
            width: 460,
            height: min(
                authModel.status == .locked ? 640 : 400,
                (NSScreen.main?.visibleFrame.height ?? 800) - 64)
        )
        .appToastOverlay()
    }

    private func resolve(approved: Bool) {
        guard
            decision.begin(
                approved: approved,
                vaultUnlocked: authModel.status == .unlocked,
                expiresAtEpochMs: request.expiresAtEpochMs
            )
        else { return }
        gpgAgentModel.resolveGpgAgentRequest(id: request.id, approved: approved)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Text(value).font(.callout).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
#endif
