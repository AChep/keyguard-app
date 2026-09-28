import Foundation

/// A request can be resolved once; unlocking never resolves it by itself.
struct GpgAgentApprovalDecision {
    private(set) var isResolving = false

    mutating func begin(approved: Bool, vaultUnlocked: Bool, expiresAtEpochMs: Int64, now: Date = .now) -> Bool {
        guard !isResolving else { return false }
        if approved {
            guard vaultUnlocked, now.timeIntervalSince1970 * 1000 < Double(expiresAtEpochMs) else { return false }
        }
        isResolving = true
        return true
    }
}
