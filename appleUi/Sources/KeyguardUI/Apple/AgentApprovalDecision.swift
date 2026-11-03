import Foundation

/// Binds one presentation's action to its original request, even if a stale
/// button callback arrives after the queue has advanced. A request can be resolved
/// once; approving needs an unlocked vault, and unlocking never resolves it by itself.
struct AgentApprovalDecision {
    let requestID: String
    let expiresAtEpochMs: Int64
    private(set) var isResolving = false

    /// - Returns: The id of the request to resolve, or `nil` to ignore the action.
    mutating func begin(
        approved: Bool,
        vaultUnlocked: Bool,
        currentRequestID: String?,
        now: Date = .now
    ) -> String? {
        guard !isResolving, currentRequestID == requestID else { return nil }
        if approved {
            guard vaultUnlocked, now.timeIntervalSince1970 * 1000 < Double(expiresAtEpochMs) else { return nil }
        }
        isResolving = true
        return requestID
    }

    /// Re-enables the actions after a resolution was abandoned before it reached
    /// the request, for example a cancelled directory grant.
    mutating func reset() {
        isResolving = false
    }
}
