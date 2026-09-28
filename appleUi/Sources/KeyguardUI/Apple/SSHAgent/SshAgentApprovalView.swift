#if os(macOS)
import SwiftUI
import Combine
import KeyguardShared

/// Per-sign approval prompt for the SSH agent. Rendered from the shared
/// `SshAgentRequestSnapshot`; Approve / Deny resolve the shared producer's
/// pending request (which unblocks or fails the signature).
struct SshAgentApprovalView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let request: SshAgentRequestSnapshot

    @State private var decision: SshAgentApprovalDecision

    init(request: SshAgentRequestSnapshot) {
        self.request = request
        _decision = State(
            initialValue: SshAgentApprovalDecision(requestID: request.id, expiresAtEpochMs: request.expiresAtEpochMs))
    }

    /// Visual countdown of the approval window: 1 (full time) → 0 (expired). The
    /// shared producer enforces the actual expiry; this only conveys it.
    @State private var timeoutProgress: Double = 1

    /// Whole seconds left until auto-deny, driven by a 1s ticker so the deadline
    /// is conveyed as text (not animation alone) and is announced to VoiceOver.
    @State private var remainingSeconds: Int = 0

    private let countdownTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            content
                .padding(20)
            // Full-width countdown bar flush at the bottom edge, mirroring the
            // shared desktop screen's LinearProgressIndicator. Paired with a
            // textual countdown so the deadline is not animation-only and is
            // announced to assistive technology.
            ProgressView(value: timeoutProgress) {
                Text(countdownText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
            }
            .progressViewStyle(.linear)
            .accessibilityLabel(L10n.agentApprovalTimeLeftLabel)
            .accessibilityValue(countdownText)
        }
        .frame(width: 420)
        .task(id: request.id) {
            let nowMs = Date().timeIntervalSince1970 * 1000
            let total = Double(request.timeoutMs)
            let remainingMs = max(0, Double(request.expiresAtEpochMs) - nowMs)
            timeoutProgress = total > 0 ? remainingMs / total : 0
            remainingSeconds = Int((remainingMs / 1000).rounded(.up))
            // Reduce Motion: skip the continuous bar animation and let the 1s
            // ticker step the value instead, so the countdown is still readable.
            if reduceMotion {
                timeoutProgress = total > 0 ? remainingMs / total : 0
            } else {
                withAnimation(.linear(duration: remainingMs / 1000)) {
                    timeoutProgress = 0
                }
            }
        }
        .onReceive(countdownTimer) { _ in
            let nowMs = Date().timeIntervalSince1970 * 1000
            let remainingMs = max(0, Double(request.expiresAtEpochMs) - nowMs)
            remainingSeconds = Int((remainingMs / 1000).rounded(.up))
            if reduceMotion {
                let total = Double(request.timeoutMs)
                timeoutProgress = total > 0 ? remainingMs / total : 0
            }
        }
    }

    private var countdownText: String {
        L10n.agentApprovalExpiresInText(L10n.secondsPlural(remainingSeconds))
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "key.horizontal.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.sshAgentRequestApprovalSignTitle)
                        .font(.headline)
                    Text(L10n.sshAgentApprovalSignText)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            GroupBox {
                VStack(alignment: .leading, spacing: 8) {
                    detailRow(
                        L10n.encryptionKey, request.keyName.isEmpty ? L10n.agentApprovalUntitledKey : request.keyName)
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

            HStack {
                Spacer()
                Button(L10n.ipcApprovalDeny, role: .cancel) {
                    resolve(approved: false)
                }
                .keyboardShortcut(.cancelAction)
                // Deliberately no keyboard shortcut: this grants an external
                // process a cryptographic signature with the user's SSH key.
                // Approval should require direct interaction with the button
                // itself, either by click or focused control activation.
                Button(L10n.ipcApprovalApprove) {
                    resolve(approved: true)
                }
                .accessibilityHint(L10n.sshAgentApprovalApproveHint)
                .buttonStyle(.borderedProminent)
            }
            .disabled(decision.isResolving)
        }
    }

    private func resolve(approved: Bool) {
        guard
            let requestID = decision.begin(
                approved: approved, currentRequestID: sshAgentModel.sshAgentRequests.first?.id)
        else { return }
        sshAgentModel.resolveSshAgentRequest(id: requestID, approved: approved)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 96, alignment: .leading)
            Text(value)
                .font(.callout)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#endif
