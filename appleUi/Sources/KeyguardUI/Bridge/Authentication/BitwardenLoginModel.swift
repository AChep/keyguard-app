import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class BitwardenLoginModel: SnapshotObserving {
    private let source: any BitwardenLoginSource
    let dialogs: DialogsModel?
    private let openExternalURL: @MainActor (String) -> Void
    /// Cancelled once the form is dismissed or signs in; the source is then never observed again.
    @ObservationIgnored private let lifetime: BridgeObservation

    init(
        source: any BitwardenLoginSource, dialogs: DialogsModel? = nil,
        openExternalURL: @escaping @MainActor (String) -> Void
    ) {
        self.source = source
        self.dialogs = dialogs
        self.openExternalURL = openExternalURL
        lifetime = BridgeObservation(cancel: { source.close() })
    }

    private(set) var login: LoginSnapshot = LoginSnapshot.companion.empty

    private(set) var loginDidSucceed = false

    private(set) var twofa: TwofaSnapshot = TwofaSnapshot.companion.empty

    /// Flips to `true` when the login producer reports a 2FA challenge, pushing
    /// the 2FA screen on top of the login screen.
    var twofaActive = false

    private(set) var twofaDidSucceed = false

    @ObservationIgnored private var loginSubscription: BridgeObservation?

    @ObservationIgnored private var twofaSubscription: BridgeObservation?

    /// Call when the login screen appears; balance with `stopLoginObservation()`.
    func startLoginObservation() {
        guard !lifetime.isCancelled else { return }
        dialogs?.startFormDialogs()
        startObservation(\.loginSubscription) { deliver in
            source.subscribeLogin(
                onChange: { snapshot in deliver { $0.login = snapshot } },
                onClose: { deliver { $0.complete() } },
                onTwofaRequired: { deliver { $0.twofaActive = true } }
            )
        }
    }

    /// Ending both subscriptions drops every callback still queued behind the completion.
    private func complete() {
        stopObservation(\.twofaSubscription)
        stopObservation(\.loginSubscription)
        lifetime.cancel()
        loginDidSucceed = true
        twofaDidSucceed = true
        dialogs?.stopFormDialogs()
    }

    func stopLoginObservation() {
        stopTwofaObservation()
        dialogs?.stopFormDialogs()
        lifetime.cancel()
        stopObservation(\.loginSubscription)
        login = LoginSnapshot.companion.empty
        loginDidSucceed = false
    }

    /// Validation runs in Kotlin and flows back through `login`.
    func setLoginField(id: String, text: String) {
        source.setLoginField(id: id, text: text)
    }

    func selectLoginRegion(key: String) {
        source.selectLoginRegion(key: key)
    }

    /// Invokes a custom-environment item action (header menu / add button).
    func invokeLoginAction(id: String) {
        source.invokeLoginAction(id: id)
    }

    /// Opens the Bitwarden registration page.
    func clickLoginRegister() {
        source.clickLoginRegister()
    }

    func submitLogin() {
        source.submitLogin()
    }

    /// Runs the 2FA producer for the challenge raised by the login producer. Call when
    /// the 2FA screen appears; balance with `stopTwofaObservation()`.
    func startTwofaObservation() {
        guard !lifetime.isCancelled else { return }
        startObservation(\.twofaSubscription, into: \.twofa, subscribe: source.subscribeTwofa)
    }

    func stopTwofaObservation() {
        source.stopTwofa()
        stopObservation(\.twofaSubscription)
        twofa = TwofaSnapshot.companion.empty
        twofaActive = false
        twofaDidSucceed = false
    }

    func setTwofaCode(text: String) {
        source.setTwofaCode(text: text)
    }

    func selectTwofaProvider(key: String) {
        source.selectTwofaProvider(key: key)
    }

    func toggleTwofaRememberMe(checked: Bool) {
        source.toggleTwofaRememberMe(checked: checked)
    }

    /// Requests a fresh verification email (email / email-new-device providers).
    func resendTwofaCode() {
        source.resendTwofaCode()
    }

    func submitTwofa() {
        source.submitTwofa()
    }

    func submitTwofaYubiKey(token: String) {
        source.submitTwofaYubiKey(token: token)
    }

    /// Opens the web vault in the system browser so the user can finish a 2FA
    /// method not supported in-app (Duo / FIDO2-WebAuthn).
    func openTwofaWebVault() {
        guard let urlString = twofa.webVaultUrl else { return }
        openExternalURL(urlString)
    }
}
