import KeyguardShared

@MainActor
protocol BitwardenLoginSource: AnyObject {
    func subscribeLogin(
        onChange: @escaping (LoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onTwofaRequired: @escaping () -> Void
    ) -> BridgeObservation
    func subscribeTwofa(onChange: @escaping (TwofaSnapshot) -> Void) -> BridgeObservation
    func stopTwofa()
    func close()
    func setLoginField(id: String, text: String)
    func selectLoginRegion(key: String)
    func invokeLoginAction(id: String)
    func clickLoginRegister()
    func submitLogin()
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
        onTwofaRequired: @escaping () -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onClose: onClose, onTwofaRequired: onTwofaRequired))
    }

    func subscribeTwofa(onChange: @escaping (TwofaSnapshot) -> Void) -> BridgeObservation {
        BridgeObservation(observeTwofa(onChange: onChange))
    }
}
