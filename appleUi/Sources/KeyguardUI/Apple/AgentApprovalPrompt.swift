#if os(macOS)
import AppKit
import SwiftUI

/// One labeled value of an approval prompt, such as the key or the caller.
struct AgentApprovalDetail {
    let label: String
    let value: String

    /// The rows that name the process that asks.
    static func caller(name: String, path: String) -> [AgentApprovalDetail] {
        var rows = [
            AgentApprovalDetail(label: L10n.agentApprovalRequestedByLabel, value: name.isEmpty ? L10n.unknown : name)
        ]
        if !path.isEmpty { rows.append(AgentApprovalDetail(label: L10n.agentApprovalPathLabel, value: path)) }
        return rows
    }

    /// The rows of the key that a request uses.
    static func key(name: String, fingerprint: String) -> [AgentApprovalDetail] {
        var rows = [
            AgentApprovalDetail(label: L10n.encryptionKey, value: name.isEmpty ? L10n.agentApprovalUntitledKey : name)
        ]
        if !fingerprint.isEmpty { rows.append(AgentApprovalDetail(label: L10n.fingerprint, value: fingerprint)) }
        return rows
    }
}

/// The labeled values of an approval prompt, grouped in a box.
struct AgentApprovalDetails: View {
    let rows: [AgentApprovalDetail]

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.label).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        Text(row.value).font(.callout).textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6)
        }
    }
}

/// The layout every agent's approval prompt shares: the request scrolls above
/// the deny and approve buttons and a countdown to its expiry.
///
/// Unlocking only unlocks the vault. A separate click approves the request, and
/// a request can be resolved once.
struct AgentApprovalPrompt<Content: View>: View {
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let expiresAtEpochMs: Int64
    private let timeoutMs: Int64?
    private let title: String
    private let systemImage: String
    private let message: String?
    private let denyTitle: String
    private let approveTitle: String
    private let approveHint: String?
    private let elevatedAccessHost: ElevatedAccessLocalHost?
    private let currentRequestID: () -> String?
    private let resolve: (_ requestID: String, _ approved: Bool, _ reset: @escaping () -> Void) -> Void
    private let content: Content

    @State private var decision: AgentApprovalDecision
    @State private var shownAt = Date()

    /// - Parameters:
    ///   - timeoutMs: The whole approval window, or `nil` to count down from
    ///     when the prompt is shown.
    ///   - elevatedAccessHost: The host of the master-password sheet, if approving
    ///     may ask for it. Another approval panel may be shown too, so only the
    ///     one being resolved hosts the sheet.
    ///   - currentRequestID: The front request of the queue, read when a button is clicked.
    ///   - resolve: Resolves the request. Calling `reset` re-enables the buttons
    ///     if the resolution was abandoned before it reached the request.
    init(
        requestID: String,
        expiresAtEpochMs: Int64,
        timeoutMs: Int64? = nil,
        title: String,
        systemImage: String,
        message: String? = nil,
        denyTitle: String = L10n.ipcApprovalDeny,
        approveTitle: String = L10n.ipcApprovalApprove,
        approveHint: String? = nil,
        elevatedAccessHost: ElevatedAccessLocalHost? = nil,
        currentRequestID: @escaping () -> String?,
        resolve: @escaping (_ requestID: String, _ approved: Bool, _ reset: @escaping () -> Void) -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.expiresAtEpochMs = expiresAtEpochMs
        self.timeoutMs = timeoutMs
        self.title = title
        self.systemImage = systemImage
        self.message = message
        self.denyTitle = denyTitle
        self.approveTitle = approveTitle
        self.approveHint = approveHint
        self.elevatedAccessHost = elevatedAccessHost
        self.currentRequestID = currentRequestID
        self.resolve = resolve
        self.content = content()
        _decision = State(initialValue: AgentApprovalDecision(requestID: requestID, expiresAtEpochMs: expiresAtEpochMs))
    }

    var body: some View {
        let prompt = VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Label(title, systemImage: systemImage).font(.headline)
                    if let message {
                        Text(message).font(.callout).foregroundStyle(.secondary)
                    }
                    content
                    AgentApprovalVaultUnlock()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            footer.padding(20)
        }
        .frame(width: 460)
        // Fits the content, but scrolls the request rather than outgrow the screen.
        .frame(maxHeight: (NSScreen.main?.visibleFrame.height ?? 800) - 64)
        .fixedSize(horizontal: false, vertical: true)
        .agentApprovalCard()
        if let elevatedAccessHost {
            prompt.localElevatedAccessHost(elevatedAccessHost, isEnabled: decision.isResolving)
        } else {
            prompt
        }
    }

    private var footer: some View {
        // Only the countdown changes over time, so the 1s timer redraws only the footer.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let expiresAt = Double(expiresAtEpochMs) / 1000
            let remaining = max(0, expiresAt - context.date.timeIntervalSince1970)
            let total = timeoutMs.map { Double($0) / 1000 } ?? expiresAt - shownAt.timeIntervalSince1970
            let countdown = L10n.agentApprovalExpiresInSecondsPlural(Int(remaining.rounded(.up)))
            VStack(spacing: 16) {
                HStack {
                    Spacer()
                    Button(denyTitle, role: .cancel) { begin(approved: false) }
                        .keyboardShortcut(.cancelAction)
                    // Deliberately no default-action shortcut: approval should require
                    // direct interaction with the button, and Return in the unlock form
                    // must never approve the request after unlocking.
                    Button(approveTitle) { begin(approved: true) }
                        .accessibilityHint(approveHint ?? "")
                        .buttonStyle(.borderedProminent)
                        .disabled(remaining <= 0 || authModel.status != .unlocked)
                }
                .disabled(decision.isResolving)
                ProgressView(value: min(remaining, total), total: max(1, total)) {
                    Text(countdown).font(.caption).foregroundStyle(.secondary)
                }
                .progressViewStyle(.linear)
                .animation(reduceMotion ? nil : .linear(duration: 1), value: remaining)
                .accessibilityLabel(L10n.agentApprovalTimeLeftLabel)
                .accessibilityValue(countdown)
            }
        }
    }

    private func begin(approved: Bool) {
        guard
            let requestID = decision.begin(
                approved: approved,
                vaultUnlocked: authModel.status == .unlocked,
                currentRequestID: currentRequestID()
            )
        else { return }
        resolve(requestID, approved) { decision.reset() }
    }
}

#endif
