import Foundation

/// Binds one presentation's action to its original request, even if a stale
/// button callback arrives after the queue has advanced.
struct SshAgentApprovalDecision {
    let requestID: String
    let expiresAtEpochMs: Int64
    private(set) var isResolving = false

    mutating func begin(approved: Bool, currentRequestID: String?, now: Date = .now) -> String? {
        guard !isResolving, currentRequestID == requestID else { return nil }
        if approved {
            guard now.timeIntervalSince1970 * 1000 < Double(expiresAtEpochMs) else { return nil }
        }
        isResolving = true
        return requestID
    }
}
