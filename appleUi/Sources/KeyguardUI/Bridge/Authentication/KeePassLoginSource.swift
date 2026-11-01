import KeyguardShared

@MainActor
protocol KeePassLoginSource: AnyObject {
    func subscribe(
        onChange: @escaping (KeePassLoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onWebDavChange: @escaping (WebDavSettingsSnapshot?) -> Void,
        onS3Change: @escaping (S3SettingsSnapshot?) -> Void
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
    /// The WebDAV and S3 settings sheets of a remote database.
    var remoteSettings: any RemoteSettingsSource { get }
    func setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Void)?)
    func resolveKeePassFilePicker(requestId: String, uri: String, name: String?, size: Int64, accessToken: String?)
    func cancelKeePassFilePicker(requestId: String)
}

extension KeePassLoginSession: KeePassLoginSource {
    var remoteSettings: any RemoteSettingsSource { settingsSheets }

    func subscribe(
        onChange: @escaping (KeePassLoginSnapshot) -> Void,
        onClose: @escaping () -> Void,
        onWebDavChange: @escaping (WebDavSettingsSnapshot?) -> Void,
        onS3Change: @escaping (S3SettingsSnapshot?) -> Void
    ) -> BridgeObservation {
        BridgeObservation(
            observe(onChange: onChange, onClose: onClose, onWebDavChange: onWebDavChange, onS3Change: onS3Change)
        )
    }
}
