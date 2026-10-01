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
                core.observeUnlock { snapshot in
                    deliver { $0.applyUnlock(unlock: snapshot) }
                })
        }
        startObservation(\.setupSubscription) { deliver in
            BridgeObservation(
                core.observeSetup { snapshot in
                    deliver { $0.applySetup(setup: snapshot) }
                })
        }
    }

    private(set) var status: VaultStatus = .loading

    private(set) var unlockPasswordError: String?

    private(set) var unlockCanSubmit = false

    private(set) var unlockAcknowledgedPassword = ""

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

    private(set) var setupPasswordError: String?

    private(set) var setupCanCreate = false

    private(set) var setupIsLoading = false

    private(set) var setupHasBiometric = false

    private(set) var setupBiometricEnabled = false

    private(set) var setupCrashlyticsEnabled = false

    private(set) var hasOnboarded: Bool = true

    @ObservationIgnored private var statusSubscription: BridgeObservation?

    @ObservationIgnored private var onboardingSubscription: BridgeObservation?

    @ObservationIgnored private var unlockSubscription: BridgeObservation?

    @ObservationIgnored private var setupSubscription: BridgeObservation?

    /// Validation (and the "can create" gate) runs in Kotlin and flows back through
    /// `observeSetup`.
    func setSetupPassword(_ text: String) {
        core.setSetupPassword(text: text)
    }

    func setSetupCrashlytics(_ enabled: Bool) {
        core.setSetupCrashlytics(enabled: enabled)
    }

    func setSetupBiometric(_ enabled: Bool) {
        core.setSetupBiometric(enabled: enabled)
    }

    func submitSetup() {
        core.submitSetup()
    }

    /// Tells the bridge whether the create screen is on screen, arming the
    /// Touch ID enrollment prompt host only while it is visible.
    func setSetupScreenVisible(_ visible: Bool) {
        core.setSetupScreenVisible(visible: visible)
    }

    /// Validation runs in Kotlin and flows back through `observeUnlock`.
    func setUnlockPassword(_ text: String) {
        core.setUnlockPassword(text: text)
    }

    func submitUnlock() {
        core.submitUnlock()
    }

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

    private func applyUnlock(unlock snapshot: UnlockSnapshot) {
        unlockPasswordError = snapshot.passwordError
        unlockCanSubmit = snapshot.canUnlock
        unlockAcknowledgedPassword = snapshot.password
        unlockIsLoading = snapshot.isLoading
        unlockHasBiometric = snapshot.hasBiometric
        unlockHasYubiKey = snapshot.hasYubiKey
        unlockHasFido2 = snapshot.hasFido2
        unlockLockReason = snapshot.lockReason
        unlockActions = snapshot.actions
    }

    private func applySetup(setup snapshot: SetupSnapshot) {
        setupPasswordError = snapshot.passwordError
        setupCanCreate = snapshot.canCreate
        setupIsLoading = snapshot.isLoading
        setupHasBiometric = snapshot.hasBiometric
        setupBiometricEnabled = snapshot.biometricEnabled
        setupCrashlyticsEnabled = snapshot.crashlyticsEnabled
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
