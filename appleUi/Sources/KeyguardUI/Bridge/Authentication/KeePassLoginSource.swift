import KeyguardShared

@MainActor
protocol KeePassLoginSource: AnyObject {
    func subscribe(
        onChange: @escaping (KeePassLoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onWebDavChange: @escaping (WebDavSettingsSnapshot?) -> Void
    ) -> BridgeObservation
    func close()
    func selectKeePassTab(key: String)
    func selectKeePassLocation(key: String)
    func pickKeePassDbFile()
    func clearKeePassDbFile()
    func pickKeePassKeyFile()
    func clearKeePassKeyFile()
    func setKeePassPassword(text: String)
    func submitKeePassLogin()
    func setWebDavField(sessionId: String, id: String, text: String)
    func submitWebDavSettings(sessionId: String)
    func testWebDavConnection(sessionId: String)
    func cancelWebDavSettings()
    func setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Void)?)
    func resolveKeePassFilePicker(requestId: String, uri: String, name: String?, size: Int64, accessToken: String?)
    func cancelKeePassFilePicker(requestId: String)
}

extension KeePassLoginSession: KeePassLoginSource {
    func subscribe(
        onChange: @escaping (KeePassLoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onWebDavChange: @escaping (WebDavSettingsSnapshot?) -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onClose: onClose, onWebDavChange: onWebDavChange))
    }
}
