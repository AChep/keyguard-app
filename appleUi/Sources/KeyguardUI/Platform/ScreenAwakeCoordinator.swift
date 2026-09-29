import Foundation
import Observation

/// Owns the process-wide idle timer so overlapping views and scenes share it safely.
@MainActor
@Observable
final class ScreenAwakeCoordinator {
    @ObservationIgnored private var requests: Set<UUID> = []
    @ObservationIgnored private let setIdleTimerDisabled: (Bool) -> Void

    init(setIdleTimerDisabled: @escaping (Bool) -> Void) {
        self.setIdleTimerDisabled = setIdleTimerDisabled
    }

    func update(id: UUID, request: ScreenAwakeRequest) {
        let wasActive = !requests.isEmpty
        if request.isActive {
            requests.insert(id)
        } else {
            requests.remove(id)
        }
        applyChange(wasActive: wasActive)
    }

    func remove(id: UUID) {
        let wasActive = !requests.isEmpty
        requests.remove(id)
        applyChange(wasActive: wasActive)
    }

    private func applyChange(wasActive: Bool) {
        let isActive = !requests.isEmpty
        if wasActive != isActive {
            setIdleTimerDisabled(isActive)
        }
    }
}
