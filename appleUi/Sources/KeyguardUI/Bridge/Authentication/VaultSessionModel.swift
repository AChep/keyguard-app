import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class VaultSessionModel {
    private let core: KeyguardCore
    private let notifications: NotificationsModel

    init(core: KeyguardCore, notifications: NotificationsModel) {
        self.core = core
        self.notifications = notifications
    }

    @ObservationIgnored private var started = false

    func start() {
        guard !started else { return }
        started = true
        fido2Subscription = BridgeObservation(
            core.observeFido2Prompt { [weak self] phase in
                Task { @MainActor [weak self] in self?.fido2Phase = phase }
            })
        statusSubscription = BridgeObservation(
            core.observeStatus { [weak self] status in
                Task { @MainActor [weak self] in
                    self?.applyStatus(status: status)
                }
            })
        // First-run onboarding: observed app-wide (not per-screen) so the root
        // banner reflects it the moment the vault unlocks and survives navigation.
        onboardingSubscription = BridgeObservation(
            core.observeOnboarding { [weak self] hasOnboarded in
                Task { @MainActor [weak self] in
                    self?.hasOnboarded = hasOnboarded.boolValue
                }
            })
        unlockSubscription = BridgeObservation(
            core.observeUnlock { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.applyUnlock(unlock: snapshot)
                }
            })
        setupSubscription = BridgeObservation(
            core.observeSetup { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.applySetup(setup: snapshot)
                }
            })
    }

    private(set) var status: VaultStatus = .loading

    private(set) var isBusy = false

    /// Current unlock-form state.
    private(set) var unlockPasswordError: String?

    private(set) var unlockCanSubmit = false

    private(set) var unlockAcknowledgedPassword = ""

    private(set) var unlockIsLoading = false

    private(set) var unlockHasBiometric = false

    /// Device has a YubiKey unlock factor enrolled (offers the YubiKey button).
    private(set) var unlockHasYubiKey = false
    private(set) var unlockHasFido2 = false
    private(set) var fido2Phase: Fido2PromptPhase = .hidden
    @ObservationIgnored private var fido2Subscription: BridgeObservation?

    func submitFido2Pin(_ pin: String) { core.submitFido2Pin(pin: pin) }
    func cancelFido2Prompt() { core.cancelFido2Prompt() }
    func triggerUnlockFido2() { core.triggerUnlockFido2() }

    /// Why the vault locked (e.g. "Locked manually"), shown above the form.
    private(set) var unlockLockReason: String?

    /// Destructive recovery actions for a forgotten password.
    private(set) var unlockActions: [UnlockActionSnapshot] = []

    /// Unlock action awaiting confirmation from the macOS Vault menu.
    var pendingUnlockAction: UnlockActionSnapshot?

    /// Current create-vault form state.
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

    /// Forwards typed text into the shared setup producer. Validation (and the
    /// "can create" gate) runs in Kotlin and flows back through `observeSetup`.
    func setSetupPassword(_ text: String) {
        core.setSetupPassword(text: text)
    }

    /// Toggles the "send crash reports" opt-in on the create screen.
    func setSetupCrashlytics(_ enabled: Bool) {
        core.setSetupCrashlytics(enabled: enabled)
    }

    /// Toggles Touch ID enrollment for the vault being created.
    func setSetupBiometric(_ enabled: Bool) {
        core.setSetupBiometric(enabled: enabled)
    }

    /// Submits the create-vault form held by the shared setup producer.
    func submitSetup() {
        core.submitSetup()
    }

    /// Tells the bridge whether the create screen is on screen, arming the
    /// Touch ID enrollment prompt host only while it is visible.
    func setSetupScreenVisible(_ visible: Bool) {
        core.setSetupScreenVisible(visible: visible)
    }

    func unlockVault(password: String) {
        run { try await self.core.unlockVault(password: password) }
    }

    /// Forwards typed text into the shared unlock producer. Validation runs in
    /// Kotlin and flows back through `observeUnlock`.
    func setUnlockPassword(_ text: String) {
        core.setUnlockPassword(text: text)
    }

    /// Submits the password held by the shared unlock producer.
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

    /// Invokes an unlock-screen escape-hatch action (e.g. "Erase data") by id.
    /// Destructive — the caller (unlock screen / macOS "Vault" menu) confirms first.
    func invokeUnlockAction(_ id: String) {
        core.invokeUnlockAction(id: id)
    }

    /// Triggers the YubiKey unlock prompt (shown only when a YubiKey factor is
    /// enrolled). The shared producer emits a prompt that the registered native
    /// transmitter resolves.
    func triggerUnlockYubiKey() {
        core.triggerUnlockYubiKey()
    }

    /// Locks the vault (menu-bar "Lock now"). The shared producer flips the
    /// observed status to `.locked`, which every scene reacts to.
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
            // AutoFill observes committed database changes independently.
        } else {
            self.status = .loading
        }
    }

    private func run(_ operation: @escaping () async throws -> Void) {
        isBusy = true
        Task { @MainActor in
            do {
                try await operation()
            } catch {
                notifications.showOperationError(error)
            }
            isBusy = false
        }
    }
}
