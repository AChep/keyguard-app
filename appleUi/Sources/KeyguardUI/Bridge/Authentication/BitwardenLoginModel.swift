import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class BitwardenLoginModel: SnapshotObserving {
    private let coreProvider: () -> KeyguardCore
    private var core: KeyguardCore { coreProvider() }
    private let observeLogin:
        (@escaping (LoginSnapshot) -> Void, @escaping () -> Void, @escaping () -> Void) -> BridgeObservation
    private let observeTwofa: (@escaping (TwofaSnapshot) -> Void, @escaping () -> Void) -> BridgeObservation
    private let openExternalURL: @MainActor (String) -> Void

    convenience init(core: KeyguardCore, links: LinkOpeningCoordinator) {
        self.init(
            coreProvider: { core },
            observeLogin: {
                BridgeObservation(core.observeBitwardenLogin(onChange: $0, onSuccess: $1, onTwofaRequired: $2))
            },
            observeTwofa: { BridgeObservation(core.observeBitwardenLoginTwofa(onChange: $0, onSuccess: $1)) },
            openExternalURL: { links.open($0, forceSystem: true) }
        )
    }

    /// Observation factories let lifecycle tests run without starting the shared DI graph.
    init(
        coreProvider: @escaping () -> KeyguardCore,
        observeLogin:
            @escaping (@escaping (LoginSnapshot) -> Void, @escaping () -> Void, @escaping () -> Void) ->
            BridgeObservation,
        observeTwofa: @escaping (@escaping (TwofaSnapshot) -> Void, @escaping () -> Void) -> BridgeObservation,
        openExternalURL: @escaping @MainActor (String) -> Void
    ) {
        self.coreProvider = coreProvider
        self.observeLogin = observeLogin
        self.observeTwofa = observeTwofa
        self.openExternalURL = openExternalURL
    }

    /// Current Bitwarden login state.
    private(set) var login: LoginSnapshot = LoginSnapshot.companion.empty

    /// Flips to `true` once the shared producer reports a successful login, so
    /// the login view can dismiss itself.
    private(set) var loginDidSucceed = false

    /// Current Bitwarden two-factor state.
    private(set) var twofa: TwofaSnapshot = TwofaSnapshot.companion.empty

    /// Flips to `true` when the login producer reports a 2FA challenge, pushing
    /// the 2FA screen on top of the login screen.
    var twofaActive = false

    /// Flips to `true` once the 2FA challenge is satisfied, so the 2FA view can
    /// dismiss itself.
    private(set) var twofaDidSucceed = false

    @ObservationIgnored private var loginSubscription: BridgeObservation?

    @ObservationIgnored private var twofaSubscription: BridgeObservation?

    /// Starts running the shared Bitwarden login producer. Call when the login
    /// screen appears; balance with `stopLoginObservation()` on disappear.
    func startLoginObservation() {
        startObservation(\.loginSubscription) { deliver in
            observeLogin(
                { snapshot in
                    deliver { $0.login = snapshot }
                },
                {
                    deliver { $0.loginDidSucceed = true }
                },
                {
                    deliver { $0.twofaActive = true }
                }
            )
        }
    }

    func stopLoginObservation() {
        stopObservation(\.loginSubscription)
        login = LoginSnapshot.companion.empty
        loginDidSucceed = false
    }

    /// Forwards typed text into a shared login field, identified by its snapshot
    /// id. Validation runs in Kotlin and flows back through `observeBitwardenLogin`.
    func setLoginField(id: String, text: String) {
        core.setLoginField(id: id, text: text)
    }

    /// Selects a server region (US / EU / Custom) by its snapshot key.
    func selectLoginRegion(key: String) {
        core.selectLoginRegion(key: key)
    }

    /// Invokes a custom-environment item action (header menu / add button).
    func invokeLoginAction(id: String) {
        core.invokeLoginAction(id: id)
    }

    /// Opens the Bitwarden registration page.
    func clickLoginRegister() {
        core.clickLoginRegister()
    }

    /// Submits the Bitwarden login held by the shared producer.
    func submitLogin() {
        core.submitLogin()
    }

    /// Starts running the shared Bitwarden 2FA producer for the challenge raised
    /// by the login producer. Call when the 2FA screen appears; balance with
    /// `stopTwofaObservation()` on disappear.
    func startTwofaObservation() {
        startObservation(\.twofaSubscription) { deliver in
            observeTwofa(
                { snapshot in
                    deliver { $0.twofa = snapshot }
                },
                {
                    deliver {
                        // Dismiss both the 2FA screen and the login screen beneath it.
                        $0.twofaDidSucceed = true
                        $0.loginDidSucceed = true
                    }
                }
            )
        }
    }

    func stopTwofaObservation() {
        stopObservation(\.twofaSubscription)
        twofa = TwofaSnapshot.companion.empty
        twofaActive = false
        twofaDidSucceed = false
    }

    /// Forwards typed text into the shared 2FA verification-code field.
    func setTwofaCode(text: String) {
        core.setTwofaCode(text: text)
    }

    /// Selects a 2FA provider by its snapshot key.
    func selectTwofaProvider(key: String) {
        core.selectTwofaProvider(key: key)
    }

    /// Toggles "remember this device" for providers that support it.
    func toggleTwofaRememberMe(checked: Bool) {
        core.toggleTwofaRememberMe(checked: checked)
    }

    /// Requests a fresh verification email (email / email-new-device providers).
    func resendTwofaCode() {
        core.resendTwofaCode()
    }

    /// Submits the code-entry 2FA challenge held by the shared producer.
    func submitTwofa() {
        core.submitTwofa()
    }

    /// Completes a YubiKey OTP challenge with the manually-typed token.
    func submitTwofaYubiKey(token: String) {
        core.submitTwofaYubiKey(token: token)
    }

    /// Opens the web vault in the system browser so the user can finish a 2FA
    /// method not supported in-app (Duo / FIDO2-WebAuthn).
    func openTwofaWebVault() {
        guard let urlString = twofa.webVaultUrl else { return }
        openExternalURL(urlString)
    }
}
