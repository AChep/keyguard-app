import Foundation
import Observation
import OSLog
@preconcurrency import KeyguardShared
@preconcurrency import AuthenticationServices

@MainActor
@Observable
final class AutofillIndexService {
    struct PreparedIndex {
        let revision: String
        let identities: [any ASCredentialIdentity]
    }

    private let core: KeyguardCore
    private let access = AutofillStoreAccess()
    private let logger = Logger(subsystem: "com.artemchep.keyguard", category: "AutoFillIndex")
    @ObservationIgnored private var subscription: BridgeObservation?
    @ObservationIgnored private var observationID: UUID?
    @ObservationIgnored private var lastEnableRequest: ContinuousClock.Instant?
    @ObservationIgnored private var reconciliationTask: Task<Void, Never>?
    @ObservationIgnored private var lastQueuedRevision: String?
    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var vaultAvailable = false
    private(set) var isEnabling = false

    @ObservationIgnored lazy var coordinator = AutofillIndexCoordinator<PreparedIndex>(
        enabled: { await ASCredentialIdentityStore.shared.state().isEnabled },
        load: { [weak self] in
            guard let self else { return nil }
            let revision = try await access.withLock { try self.access.revision() }
            guard let snapshot = try await core.loadAutofillIndex() else { return nil }
            if snapshot.skippedPasskeys > 0 {
                logger.warning("Skipped \(snapshot.skippedPasskeys, privacy: .public) invalid passkey identities")
            }
            var identities: [any ASCredentialIdentity] = snapshot.passwords.map { identity in
                ASPasswordCredentialIdentity(
                    serviceIdentifier: ASCredentialServiceIdentifier(
                        identifier: identity.serviceIdentifier, type: .URL),
                    user: identity.user, recordIdentifier: identity.recordId)
            }
            if #available(iOS 18, macOS 15, *) {
                identities += snapshot.oneTimeCodes.map { identity in
                    ASOneTimeCodeCredentialIdentity(
                        serviceIdentifier: ASCredentialServiceIdentifier(
                            identifier: identity.serviceIdentifier, type: .URL),
                        label: identity.user.isEmpty ? identity.serviceIdentifier : identity.user,
                        recordIdentifier: identity.recordId)
                }
            }
            identities += snapshot.passkeys.map { identity in
                ASPasskeyCredentialIdentity(
                    relyingPartyIdentifier: identity.rpId, userName: identity.userName,
                    credentialID: identity.credentialId.toData(), userHandle: identity.userHandle.toData(),
                    recordIdentifier: identity.recordId)
            }
            return PreparedIndex(revision: revision, identities: identities)
        },
        replace: { [weak self] snapshot, isCurrent in
            guard let self else { return true }
            return try await access.withLock {
                guard isCurrent(), try self.access.revision() == snapshot.revision else { return false }
                try await ASCredentialIdentityStore.shared.replaceCredentialIdentities(snapshot.identities)
                self.logger.info("Indexed \(snapshot.identities.count, privacy: .public) credential identities")
                return true
            }
        },
        reportFailure: { [weak self] error in
            let error = error as NSError
            self?.logger.error("Index update failed, code \(error.code, privacy: .public)")
        }
    )

    init(core: KeyguardCore) { self.core = core }

    func start() {
        guard subscription == nil else { return }
        let id = UUID()
        observationID = id
        subscription = BridgeObservation(
            core.observeAutofillChanges(
                onChange: { [weak self] available in
                    Task { @MainActor [weak self] in
                        guard let self, observationID == id else { return }
                        vaultAvailable = available.boolValue
                        if available.boolValue { reconcileExtensionChanges() }
                        coordinator.refresh(available: available.boolValue)
                    }
                },
                onFailure: { [weak self] in
                    Task { @MainActor [weak self] in
                        guard let self, observationID == id else { return }
                        subscription?.cancel()
                        subscription = nil
                        coordinator.fail()
                    }
                }))
    }

    func refreshAutofillIdentities() {
        start()
        coordinator.refresh()
    }

    func setForeground(_ foreground: Bool) {
        self.foreground = foreground
        if foreground {
            start()
            reconcileExtensionChanges()
        }
        coordinator.setForeground(foreground)
    }

    /// SQLDelight listeners are process-local. Queue host-app synchronization when
    /// the extension has committed a change, including on macOS where losing focus
    /// does not necessarily stop/restart the normal sync worker.
    private func reconcileExtensionChanges() {
        guard foreground, vaultAvailable, reconciliationTask == nil else { return }
        reconciliationTask = Task { [weak self] in
            guard let self else { return }
            defer { reconciliationTask = nil }
            do {
                let revision = try await access.withLock { try self.access.revision() }
                guard foreground, vaultAvailable, !revision.isEmpty, revision != lastQueuedRevision else { return }
                lastQueuedRevision = revision
                core.syncVault()
            } catch {
                logger.error(
                    "Extension change reconciliation failed, code \((error as NSError).code, privacy: .public)")
            }
        }
    }

    func openSettings() async {
        do { try await ASSettingsHelper.openCredentialProviderAppSettings() } catch { coordinator.fail() }
    }

    func enable() async {
        guard !isEnabling else { return }
        if let lastEnableRequest, lastEnableRequest.duration(to: ContinuousClock.now) < .seconds(10) { return }
        lastEnableRequest = ContinuousClock.now
        isEnabling = true
        defer { isEnabling = false; refreshAutofillIdentities() }
        if #available(iOS 18, macOS 15, *) {
            _ = await ASSettingsHelper.requestToTurnOnCredentialProviderExtension()
        } else {
            do { try await ASSettingsHelper.openCredentialProviderAppSettings() } catch { coordinator.fail() }
        }
    }
}
