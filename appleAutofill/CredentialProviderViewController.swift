import AuthenticationServices
import SwiftUI
import KeyguardShared
import OSLog

#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// AutoFill credential-provider extension principal class, shared by the macOS and iOS
/// extensions. Links the same shared `KeyguardShared` framework as the app and resolves
/// the selected credential via `KeyguardCore.loadAutofillCredential(recordId:)` against
/// the App-Group vault.
///
/// `ASCredentialProviderViewController` is an `NSViewController` on macOS and a
/// `UIViewController` on iOS, so the only platform split in this file is how the
/// in-extension SwiftUI is hosted (`presentHosted`). Everything else — the request
/// dispatch, TOTP, and passkey assertion/registration — is identical.
///
/// This file is compiled directly into each appex target (see the `../appleAutofill`
/// source entry in both project.yml files) rather than vended as a package product: the
/// principal class is resolved by name from Info.plist as
/// `$(PRODUCT_MODULE_NAME).CredentialProviderViewController`, and nothing in the appex
/// references it from Swift, so it must live in the target's own module to be safe from
/// dead-stripping.
///
/// Cross-process keychain sharing and system request routing must also be verified
/// on a signed device build; compilation alone cannot establish those contracts.
class CredentialProviderViewController: ASCredentialProviderViewController {
    /// The appex is its own process, so it builds its own shared graph over the
    /// App-Group vault. Installing the Keychain Services bridge first lets the shared
    /// biometric cipher read the unlock key from the shared keychain group.
    private enum RegistrationFailure: Error { case excludedCredential }

    private var activeCore: KeyguardCore?
    private let storeAccess = AutofillStoreAccess()
    private let logger = Logger(subsystem: "com.artemchep.keyguard", category: "AutoFillRequest")
    private var requestedServiceIdentifiers: [String] = []
    private var startedAt: ContinuousClock.Instant?
    private var requestTask: Task<Void, Never>?
    private var requestGeneration = 0
    private var teardownTask: Task<Void, Never>?
    #if os(iOS)
    private var backgroundObserver: NSObjectProtocol?

    override func viewDidLoad() {
        super.viewDidLoad()
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.activeCore != nil else { return }
                self.cancel(.userCanceled)
            }
        }
    }

    // Isolated so the observer registered on the main actor is removed there too.
    isolated deinit {
        if let backgroundObserver {
            NotificationCenter.default.removeObserver(backgroundObserver)
        }
    }
    #endif

    private var core: KeyguardCore {
        if let activeCore { return activeCore }
        installKeychainBridge()
        let started = ContinuousClock.now
        let value = KeyguardCore(runtime: .autofill)
        logger.info("AutoFill core initialized in \(started.duration(to: .now).description, privacy: .public)")
        activeCore = value
        return value
    }

    /// Each presented request owns its graph. A retained extension must never reuse
    /// the unlocked session belonging to a completed or cancelled request.
    private func beginRequest() -> Bool {
        guard activeCore == nil, teardownTask == nil else {
            cancel(.failed)
            return false
        }
        requestGeneration += 1
        guard AutofillStoreAccess.sharedDirectory != nil else {
            showError(L("autofill_shared_storage_unavailable"))
            return false
        }
        startedAt = .now
        logger.info("Interactive credential request started")
        return true
    }

    private func isCurrent(_ candidate: KeyguardCore) -> Bool {
        activeCore === candidate && !Task.isCancelled
    }

    // MARK: - Passwords

    /// Authentication is scoped to the presented request. Never load vault keys or
    /// start LocalAuthentication from a noninteractive extension entry point.
    override func provideCredentialWithoutUserInteraction(
        for credentialIdentity: ASPasswordCredentialIdentity
    ) {
        cancel(.userInteractionRequired)
    }

    /// The user tapped a saved login but interaction is required (e.g. unlock). Hosts
    /// the in-extension unlock screen; once the vault is unlocked the picked credential
    /// is resolved and the request completed.
    override func prepareInterfaceToProvideCredential(
        for credentialIdentity: ASPasswordCredentialIdentity
    ) {
        requestedServiceIdentifiers = [credentialIdentity.serviceIdentifier.identifier]
        let recordId = credentialIdentity.recordIdentifier
        presentUnlock { [weak self] in self?.completePassword(recordId: recordId) }
    }

    /// The user opened the AutoFill list manually (no auto-match). Hosts a SwiftUI list
    /// of logins matching `serviceIdentifiers` (ranked by the shared `GetSuggestions`
    /// matcher), unlocking the vault inline first when needed, and completes with the
    /// chosen credential.
    override func prepareCredentialList(
        for serviceIdentifiers: [ASCredentialServiceIdentifier]
    ) {
        guard beginRequest() else { return }
        let ids = serviceIdentifiers.map { $0.identifier }
        requestedServiceIdentifiers = ids
        let core = core
        let model = AutofillListModel(
            core: core,
            mode: .passwords,
            serviceIdentifiers: ids,
            onPick: { [weak self] recordId in
                guard let self, self.activeCore === core else { return }
                self.completePassword(recordId: recordId)
            },
            onCancel: { [weak self] in
                guard let self, self.activeCore === core else { return }
                self.cancel(.userCanceled)
            }
        )
        presentHosted(AutofillCredentialListView(model: model))
    }

    // MARK: - Unified credential requests (macOS 14+ / iOS 17+)

    /// Both platforms route every credential type through the generic request. Dispatch
    /// on the concrete subtype: passwords and one-time codes are handled here; passkeys
    /// (`ASPasskeyCredentialRequest`) use the shared WebAuthn authenticator.
    ///
    /// `ASOneTimeCodeCredentialRequest` only exists on macOS 15 / iOS 18, so it is
    /// checked ahead of the switch under a combined availability guard. The iOS
    /// deployment target already satisfies it; the guard is load-bearing on macOS,
    /// whose target is 14.0. The subtypes are mutually exclusive, so testing it first
    /// does not change which branch any request takes.
    override func provideCredentialWithoutUserInteraction(
        for credentialRequest: any ASCredentialRequest
    ) {
        if #available(macOS 15.0, iOS 18.0, *),
            let otpRequest = credentialRequest as? ASOneTimeCodeCredentialRequest
        {
            if let recordId = (otpRequest.credentialIdentity as? ASOneTimeCodeCredentialIdentity)?
                .recordIdentifier
            {
                provideOneTimeCodeWithoutUserInteraction(recordId: recordId)
            } else {
                cancel(.credentialIdentityNotFound)
            }
            return
        }
        switch credentialRequest {
        case let passwordRequest as ASPasswordCredentialRequest:
            if let identity = passwordRequest.credentialIdentity as? ASPasswordCredentialIdentity {
                provideCredentialWithoutUserInteraction(for: identity)
            } else {
                cancel(.credentialIdentityNotFound)
            }
        case let passkeyRequest as ASPasskeyCredentialRequest:
            providePasskeyWithoutUserInteraction(passkeyRequest)
        default:
            cancel(.failed)
        }
    }

    override func prepareInterfaceToProvideCredential(
        for credentialRequest: any ASCredentialRequest
    ) {
        if #available(macOS 15.0, iOS 18.0, *),
            let otpRequest = credentialRequest as? ASOneTimeCodeCredentialRequest
        {
            let recordId = (otpRequest.credentialIdentity as? ASOneTimeCodeCredentialIdentity)?
                .recordIdentifier
            presentUnlock { [weak self] in self?.completeOneTimeCode(recordId: recordId) }
            return
        }
        switch credentialRequest {
        case let passwordRequest as ASPasswordCredentialRequest:
            if let identity = passwordRequest.credentialIdentity as? ASPasswordCredentialIdentity {
                prepareInterfaceToProvideCredential(for: identity)
            } else {
                cancel(.credentialIdentityNotFound)
            }
        case let passkeyRequest as ASPasskeyCredentialRequest:
            prepareInterfaceForPasskeyAssertion(passkeyRequest)
        default:
            cancel(.failed)
        }
    }

    // MARK: - One-time codes (TOTP)

    /// The user opened the verification-code AutoFill list manually. Hosts the picker
    /// restricted to logins carrying a TOTP secret, unlocking inline if needed.
    override func prepareOneTimeCodeCredentialList(
        for serviceIdentifiers: [ASCredentialServiceIdentifier]
    ) {
        guard beginRequest() else { return }
        let ids = serviceIdentifiers.map { $0.identifier }
        requestedServiceIdentifiers = ids
        let core = core
        let model = AutofillListModel(
            core: core,
            mode: .oneTimeCodes,
            serviceIdentifiers: ids,
            onPick: { [weak self] recordId in
                guard let self, self.activeCore === core else { return }
                self.completeOneTimeCode(recordId: recordId)
            },
            onCancel: { [weak self] in
                guard let self, self.activeCore === core else { return }
                self.cancel(.userCanceled)
            }
        )
        presentHosted(AutofillCredentialListView(model: model))
    }

    /// One-time codes require the same presented authentication flow as passwords.
    private func provideOneTimeCodeWithoutUserInteraction(recordId: String) {
        cancel(.userInteractionRequired)
    }

    /// Resolves and completes the one-time code for the picked/selected record.
    private func completeOneTimeCode(recordId: String?) {
        guard let recordId else {
            cancel(.credentialIdentityNotFound)
            return
        }
        let core = core
        requestTask = Task { [self] in
            if let code = try? await core.loadOneTimeCode(recordId: recordId), !code.isEmpty {
                guard isCurrent(core) else { return }
                completeOneTimeCode(code: code)
            } else {
                guard isCurrent(core) else { return }
                cancel(.failed)
            }
        }
    }

    private func completeOneTimeCode(code: String) {
        if #available(macOS 15.0, iOS 18.0, *) {
            finish { [self] in
                extensionContext.completeOneTimeCodeRequest(
                    using: ASOneTimeCodeCredential(code: code)
                )
            }
        } else {
            cancel(.failed)
        }
    }

    // MARK: - Passkeys

    /// Passkey assertions require the presented authentication flow.
    private func providePasskeyWithoutUserInteraction(_ request: ASPasskeyCredentialRequest) {
        cancel(.userInteractionRequired)
    }

    /// Passkey assertion with UI: unlock the vault (the unlock counts as user
    /// verification), then sign for the requested credential.
    private func prepareInterfaceForPasskeyAssertion(_ request: ASPasskeyCredentialRequest) {
        guard let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity,
            let recordId = identity.recordIdentifier
        else {
            cancel(.credentialIdentityNotFound)
            return
        }
        let clientDataHash = request.clientDataHash
        let userVerification = request.userVerificationPreference.rawValue
        presentUnlock { [weak self] in
            self?.completePasskeyAssertion(
                recordId: recordId, rpId: identity.relyingPartyIdentifier,
                credentialID: identity.credentialID, clientDataHash: clientDataHash,
                allowed: [identity.credentialID], userVerification: userVerification)
        }
    }

    /// The user manually opened the passkey AutoFill list. Unlocks, then asserts the
    /// stored passkey matching the request's relying party (and allow-list, if any).
    override func prepareCredentialList(
        for serviceIdentifiers: [ASCredentialServiceIdentifier],
        requestParameters: ASPasskeyCredentialRequestParameters
    ) {
        let rpId = requestParameters.relyingPartyIdentifier
        let allowed = requestParameters.allowedCredentials
        let clientDataHash = requestParameters.clientDataHash
        let userVerification = requestParameters.userVerificationPreference.rawValue
        presentUnlock { [weak self] in
            self?.assertMatchingPasskey(
                rpId: rpId, allowed: allowed, clientDataHash: clientDataHash,
                userVerification: userVerification)
        }
    }

    private func assertMatchingPasskey(rpId: String, allowed: [Data], clientDataHash: Data, userVerification: String) {
        let core = core
        requestTask = Task { [self] in
            let identities: [PasskeyIdentitySnapshot]
            do {
                identities = try await core.loadMatchingPasskeyIdentities(
                    rpId: rpId, allowedCredentialIds: allowed.map { $0.toKotlinByteArray() })
            } catch {
                guard isCurrent(core) else { return }
                cancel(.failed)
                return
            }
            guard isCurrent(core) else {
                return
            }
            guard !identities.isEmpty else {
                showError(L("autofill_no_matching_logins"))
                return
            }
            presentHosted(
                AutofillPasskeyListView(
                    identities: identities,
                    onPick: { [weak self] match in
                        guard let self, self.activeCore === core else { return }
                        self.completePasskeyAssertion(
                            recordId: match.recordId, rpId: match.rpId,
                            credentialID: match.credentialId.toData(), clientDataHash: clientDataHash,
                            allowed: allowed, userVerification: userVerification)
                    },
                    onCancel: { [weak self] in
                        guard let self, self.activeCore === core else { return }
                        self.cancel(.userCanceled)
                    }))
        }
    }

    private func completePasskeyAssertion(
        recordId: String, rpId: String, credentialID: Data, clientDataHash: Data,
        allowed: [Data], userVerification: String
    ) {
        let core = core
        requestTask = Task { [self] in
            if let assertion = try? await core.assertPasskey(
                recordId: recordId,
                clientDataHash: clientDataHash.toKotlinByteArray(),
                expectedRpId: rpId,
                expectedCredentialId: credentialID.toKotlinByteArray(),
                allowedCredentialIds: allowed.map { $0.toKotlinByteArray() },
                userVerification: userVerification,
                userVerified: true
            ) {
                guard isCurrent(core) else { return }
                completeAssertion(assertion, clientDataHash: clientDataHash)
            } else {
                guard isCurrent(core) else { return }
                cancel(.failed)
            }
        }
    }

    private func completeAssertion(_ assertion: PasskeyAssertionSnapshot, clientDataHash: Data) {
        let credential = ASPasskeyAssertionCredential(
            userHandle: assertion.userHandle.toData(),
            relyingParty: assertion.rpId,
            signature: assertion.signature.toData(),
            clientDataHash: clientDataHash,
            authenticatorData: assertion.authenticatorData.toData(),
            credentialID: assertion.credentialId.toData()
        )
        finish { [self] in
            extensionContext.completeAssertionRequest(using: credential)
        }
    }

    /// Passkey registration: let the user pick an existing login to attach the new
    /// passkey to (logins matching the relying-party domain), generate + store it, and
    /// return the attestation.
    override func prepareInterface(forPasskeyRegistration registrationRequest: any ASCredentialRequest) {
        guard let request = registrationRequest as? ASPasskeyCredentialRequest,
            let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity
        else {
            cancel(.failed)
            return
        }
        guard beginRequest() else { return }
        guard request.supportedAlgorithms.contains(.ES256) else {
            showError(L("autofill_unsupported_algorithm"))
            return
        }
        let excluded: [Data]
        if #available(macOS 15, iOS 18, *) {
            excluded = request.excludedCredentials?.map(\.credentialID) ?? []
        } else {
            excluded = []
        }
        let rpId = identity.relyingPartyIdentifier
        let userName = identity.userName
        let userHandle = identity.userHandle
        let clientDataHash = request.clientDataHash
        let userVerification = request.userVerificationPreference.rawValue
        let core = core
        let model = AutofillListModel(
            core: core,
            mode: .registration,
            serviceIdentifiers: [rpId],
            onPick: { [weak self] cipherRecordId in
                guard let self, self.activeCore === core else { return }
                self.completePasskeyRegistration(
                    cipherRecordId: cipherRecordId,
                    excluded: excluded,
                    rpId: rpId,
                    userName: userName,
                    userHandle: userHandle,
                    clientDataHash: clientDataHash,
                    userVerification: userVerification
                )
            },
            onCancel: { [weak self] in
                guard let self, self.activeCore === core else { return }
                self.cancel(.userCanceled)
            }
        )
        presentHosted(AutofillCredentialListView(model: model))
    }

    private func completePasskeyRegistration(
        cipherRecordId: String,
        excluded: [Data],
        rpId: String,
        userName: String,
        userHandle: Data,
        clientDataHash: Data,
        userVerification: String
    ) {
        let core = core
        requestTask = Task { [self] in
            do {
                let registration = try await storeAccess.withLock {
                    if try await core.hasExcludedPasskeyCredential(
                        rpId: rpId, credentialIds: excluded.map { $0.toKotlinByteArray() }
                    ).boolValue {
                        throw RegistrationFailure.excludedCredential
                    }
                    guard self.isCurrent(core) else { throw CancellationError() }
                    // Advance before the database write, including when the process
                    // exits after committing but before publishing the identity.
                    try self.storeAccess.markChanged()
                    guard
                        let registration = try await core.createPasskey(
                            cipherRecordId: cipherRecordId,
                            rpId: rpId,
                            rpName: nil,
                            userName: userName,
                            userDisplayName: nil,
                            userHandle: userHandle.toKotlinByteArray(),
                            userVerification: userVerification,
                            userVerified: true
                        )
                    else { throw NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.Code.failed.rawValue) }
                    // Publish the newly stored passkey before returning to Safari. The host
                    // app may remain suspended throughout registration and the next sign-in,
                    // so its normal index refresh cannot make this credential discoverable.
                    let identity = registration.identity
                    let indexedCredential = ASPasskeyCredentialIdentity(
                        relyingPartyIdentifier: identity.rpId,
                        userName: identity.userName,
                        credentialID: identity.credentialId.toData(),
                        userHandle: identity.userHandle.toData(),
                        recordIdentifier: identity.recordId
                    )
                    do {
                        try await ASCredentialIdentityStore.shared.saveCredentialIdentities([indexedCredential])
                    } catch {
                        // Persistence succeeded. Do not fail registration and orphan
                        // the saved credential just because QuickType indexing failed.
                        self.logger.error(
                            "Passkey index update failed, code \((error as NSError).code, privacy: .public)")
                    }
                    return registration
                }
                guard isCurrent(core) else { return }
                let credential = ASPasskeyRegistrationCredential(
                    relyingParty: registration.rpId,
                    clientDataHash: clientDataHash,
                    credentialID: registration.credentialId.toData(),
                    attestationObject: registration.attestationObject.toData()
                )
                finish { [self] in
                    await extensionContext.completeRegistrationRequest(using: credential)
                }
            } catch RegistrationFailure.excludedCredential {
                guard isCurrent(core) else { return }
                if #available(macOS 15, iOS 18, *) { cancel(.matchedExcludedCredential) } else { cancel(.failed) }
            } catch {
                guard isCurrent(core) else { return }
                logger.error("Passkey registration failed, code \((error as NSError).code, privacy: .public)")
                showError(L("autofill_registration_failed"))
            }
        }
    }

    // MARK: - Helpers

    /// Resolves the picked password credential against the now-unlocked vault and
    /// completes the request (or cancels if it can't be resolved).
    private func completePassword(recordId: String?) {
        guard let recordId else {
            cancel(.credentialIdentityNotFound)
            return
        }
        let core = core
        requestTask = Task { [self] in
            if let credential = try? await core.loadAutofillCredential(recordId: recordId) {
                guard isCurrent(core) else { return }
                complete(user: credential.user, password: credential.password)
            } else {
                guard isCurrent(core) else { return }
                showError(L("autofill_request_failed")) { [weak self] in
                    self?.presentPasswordPicker()
                }
            }
        }
    }

    /// Hosts the in-extension unlock screen, invoking `onUnlocked` once the shared vault
    /// reaches the unlocked state.
    private func presentUnlock(onUnlocked: @escaping () -> Void) {
        guard beginRequest() else { return }
        let core = core
        let model = AutofillUnlockModel(
            core: core,
            onUnlocked: { [weak self] in
                guard let self, self.activeCore === core else { return }
                onUnlocked()
            },
            onCancel: { [weak self] in
                guard let self, self.activeCore === core else { return }
                self.cancel(.userCanceled)
            }
        )
        presentHosted(AutofillUnlockView(model: model))
    }

    /// Embeds a SwiftUI root as a child view filling the controller.
    private func presentHosted<V: View>(_ rootView: V) {
        removeHostedContent()
        #if os(macOS)
        let host = NSHostingController(rootView: rootView)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.width, .height]
        view.addSubview(host.view)
        #else
        let host = UIHostingController(rootView: rootView)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        #endif
    }

    /// Stop the UI's prompt/observers, cancel outstanding work, and await shared
    /// session disposal before telling AuthenticationServices the request is over.
    private func finish(_ completion: @escaping () async -> Void) {
        if let startedAt {
            logger.info(
                "Credential request finished after \(startedAt.duration(to: .now).description, privacy: .public)")
            self.startedAt = nil
        }
        let retiringCore = activeCore
        activeCore = nil
        requestTask?.cancel()
        requestTask = nil
        removeHostedContent()
        requestGeneration += 1
        let generation = requestGeneration
        let previousTeardown = teardownTask
        teardownTask = Task {
            await previousTeardown?.value
            try? await retiringCore?.closeAutofillSession()
            guard requestGeneration == generation else { return }
            teardownTask = nil
            await completion()
        }
    }

    #if os(macOS)
    override func viewDidDisappear() {
        super.viewDidDisappear()
        if activeCore != nil { finish {} }
    }
    #else
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if activeCore != nil { finish {} }
    }
    #endif

    private func removeHostedContent() {
        for child in children {
            #if os(iOS)
            child.willMove(toParent: nil)
            #endif
            child.view.removeFromSuperview()
            child.removeFromParent()
        }
    }

    private func complete(user: String, password: String) {
        let passwordCredential = ASPasswordCredential(user: user, password: password)
        finish { [self] in
            extensionContext.completeRequest(
                withSelectedCredential: passwordCredential,
                completionHandler: nil
            )
        }
    }

    private func cancel(_ code: ASExtensionError.Code) {
        logger.info("Credential request cancelled, code \(code.rawValue, privacy: .public)")
        finish { [self] in
            extensionContext.cancelRequest(
                withError: NSError(domain: ASExtensionErrorDomain, code: code.rawValue)
            )
        }
    }

    private func presentPasswordPicker() {
        guard let core = activeCore else { return }
        let model = AutofillListModel(
            core: core, mode: .passwords, serviceIdentifiers: requestedServiceIdentifiers,
            onPick: { [weak self] recordId in
                guard let self, self.activeCore === core else { return }
                self.completePassword(recordId: recordId)
            },
            onCancel: { [weak self] in
                guard let self, self.activeCore === core else { return }
                self.cancel(.userCanceled)
            })
        presentHosted(AutofillCredentialListView(model: model))
    }

    private func showError(_ message: String, onRetry: (() -> Void)? = nil) {
        let generation = requestGeneration
        let retry: (() -> Void)? = onRetry.map { action in
            { [weak self] in
                guard self?.requestGeneration == generation else { return }
                action()
            }
        }
        presentHosted(
            AutofillErrorView(message: message, onRetry: retry) { [weak self] in
                guard self?.requestGeneration == generation else { return }
                self?.cancel(.userCanceled)
            })
    }
}
