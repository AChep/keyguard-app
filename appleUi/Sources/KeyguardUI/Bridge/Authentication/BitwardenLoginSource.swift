import KeyguardShared

@MainActor
protocol BitwardenLoginSource: AnyObject {
    var startsWithSso: Bool { get }
    func subscribeLogin(
        onChange: @escaping (LoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onTwofaRequired: @escaping () -> Void,
        onTwofaFinished: @escaping () -> Void,
        onSsoRequired: @escaping () -> Void
    ) -> BridgeObservation
    func subscribeSso(onChange: @escaping (SsoLoginSnapshot) -> Void) -> BridgeObservation
    func stopSso()
    func subscribeTwofa(onChange: @escaping (TwofaSnapshot) -> Void) -> BridgeObservation
    func stopTwofa()
    func close()
    func setLoginField(id: String, text: String)
    func selectLoginRegion(key: String)
    func invokeLoginAction(id: String)
    func clickLoginRegister()
    func startSso()
    func setSsoField(id: String, text: String)
    func unlockSso()
    func retrySso()
    func completeSso(browserId: String, callback: String?, failed: Bool)
    func submitLogin()
    func discoverLoginServer()
    func setTwofaCode(text: String)
    func selectTwofaProvider(key: String)
    func toggleTwofaRememberMe(checked: Bool)
    func resendTwofaCode()
    func submitTwofa()
    func submitTwofaYubiKey(token: String)
}

extension BitwardenLoginSession: BitwardenLoginSource {
    func subscribeLogin(
        onChange: @escaping (LoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onTwofaRequired: @escaping () -> Void,
        onTwofaFinished: @escaping () -> Void,
        onSsoRequired: @escaping () -> Void
    ) -> BridgeObservation {
        BridgeObservation(
            observe(
                onChange: onChange, onClose: onClose, onTwofaRequired: onTwofaRequired,
                onTwofaFinished: onTwofaFinished, onSsoRequired: onSsoRequired))
    }

    func subscribeSso(onChange: @escaping (SsoLoginSnapshot) -> Void) -> BridgeObservation {
        BridgeObservation(observeSso(onChange: onChange))
    }

    func subscribeTwofa(onChange: @escaping (TwofaSnapshot) -> Void) -> BridgeObservation {
        BridgeObservation(observeTwofa(onChange: onChange))
    }
}
