import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class VaultSessionModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    func start() {
        startObservation(\.fido2Subscription, into: \.fido2Phase, observe: core.observeFido2Prompt)
        startObservation(\.statusSubscription) { deliver in
            BridgeObservation(
                core.observeStatus { status in
                    deliver { $0.applyStatus(status: status) }
                })
        }
        // First-run onboarding: observed app-wide (not per-screen) so the root
        // banner reflects it the moment the vault unlocks and survives navigation.
        startObservation(\.onboardingSubscription) { deliver in
            BridgeObservation(
                core.observeOnboarding { hasOnboarded in
                    deliver { $0.hasOnboarded = hasOnboarded.boolValue }
                })
        }
        startObservation(\.unlockSubscription) { deliver in
            BridgeObservation(
                core.observeUnlockOptions { snapshot in
                    deliver { $0.applyUnlock(unlock: snapshot) }
                })
        }

    }

    private(set) var status: VaultStatus = .loading

    private(set) var unlockIsLoading = false

    private(set) var unlockHasBiometric = false

    private(set) var unlockHasYubiKey = false
    private(set) var unlockHasFido2 = false
    private(set) var fido2Phase: Fido2PromptPhase = .hidden
    @ObservationIgnored private var fido2Subscription: BridgeObservation?

    func submitFido2Pin(_ pin: String) { core.submitFido2Pin(pin: pin) }
    func cancelFido2Prompt() { core.cancelFido2Prompt() }
    func triggerUnlockFido2() { core.triggerUnlockFido2() }

    private(set) var unlockLockReason: String?

    /// Destructive recovery actions for a forgotten password.
    private(set) var unlockActions: [UnlockActionSnapshot] = []

    /// Unlock action awaiting confirmation from the macOS Vault menu.
    var pendingUnlockAction: UnlockActionSnapshot?

    private(set) var hasOnboarded: Bool = true

    @ObservationIgnored private var statusSubscription: BridgeObservation?

    @ObservationIgnored private var onboardingSubscription: BridgeObservation?

    @ObservationIgnored private var unlockSubscription: BridgeObservation?

    func makeUnlockSession() -> MasterPasswordSession { core.makeUnlockSession() }

    func makeSetupSession() -> MasterPasswordSession { core.makeSetupSession() }

    func triggerUnlockBiometric() {
        core.triggerUnlockBiometric()
    }

    private var unlockScreenVisibleCount = 0

    func setUnlockScreenVisible(_ visible: Bool) {
        let was = unlockScreenVisibleCount > 0
        unlockScreenVisibleCount = max(0, unlockScreenVisibleCount + (visible ? 1 : -1))
        let now = unlockScreenVisibleCount > 0
        if was != now { core.setUnlockScreenVisible(visible: now) }
    }

    /// Invokes an unlock-screen escape-hatch action (e.g. "Erase data"). Destructive:
    /// the caller confirms first.
    func invokeUnlockAction(_ id: String) {
        core.invokeUnlockAction(id: id)
    }

    /// The shared producer emits a prompt that the registered native transmitter resolves.
    func triggerUnlockYubiKey() {
        core.triggerUnlockYubiKey()
    }

    /// The shared producer flips the observed status to `.locked`, which every scene
    /// reacts to.
    func lockVault() {
        core.lockVault()
    }

    func markOnboarded() {
        core.markOnboarded()
    }

    private func applyUnlock(unlock snapshot: UnlockOptionsSnapshot) {
        unlockIsLoading = snapshot.isLoading
        unlockHasBiometric = snapshot.hasBiometric
        unlockHasYubiKey = snapshot.hasYubiKey
        unlockHasFido2 = snapshot.hasFido2
        unlockLockReason = snapshot.lockReason
        unlockActions = snapshot.actions
    }

    private func applyStatus(status: KeyguardVaultStatus) {
        if status == KeyguardVaultStatus.needsCreate {
            self.status = .needsCreate
        } else if status == KeyguardVaultStatus.locked {
            self.status = .locked
        } else if status == KeyguardVaultStatus.unlocked {
            self.status = .unlocked
        } else {
            self.status = .loading
        }
    }
}
