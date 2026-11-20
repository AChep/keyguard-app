import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AppInformationModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore, bundle: Bundle = .main) {
        self.core = core
        self.appVersion = Self.formatAppVersion(
            version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            build: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
    }

    let appVersion: String

    private(set) var appInformation: AppInformationSnapshot = AppInformationSnapshot.companion.empty

    @ObservationIgnored private let appInformationObservation = SharedObservation()

    @ObservationIgnored private var appInformationSubscription: BridgeObservation?

    static func formatAppVersion(version: String?, build: String?) -> String {
        guard let version, !version.isEmpty else { return L10n.unknown }
        guard let build, !build.isEmpty else { return version }
        return "\(version) (\(build))"
    }

    func startAppInformationObservation() {
        appInformationObservation.acquire {
            sharedSnapshotObservation(
                \.appInformationSubscription, into: \.appInformation, empty: AppInformationSnapshot.companion.empty,
                observe: core.observeAppInformation)
        }
    }

    func stopAppInformationObservation() {
        appInformationObservation.release()
    }

    /// A newer release, or nil when there is none.
    private(set) var appUpdate: AppUpdateSnapshot?

    private(set) var appUpdateSettings: AppUpdateSettingsSnapshot = AppUpdateSettingsSnapshot.companion.empty

    @ObservationIgnored private let appUpdateObservation = SharedObservation()

    @ObservationIgnored private var appUpdateSubscription: BridgeObservation?

    @ObservationIgnored private let appUpdateSettingsObservation = SharedObservation()

    @ObservationIgnored private var appUpdateSettingsSubscription: BridgeObservation?

    func startAppUpdateObservation() {
        appUpdateObservation.acquire {
            sharedSnapshotObservation(
                \.appUpdateSubscription, into: \.appUpdate, empty: nil,
                observe: core.observeAppUpdate)
        }
    }

    func stopAppUpdateObservation() {
        appUpdateObservation.release()
    }

    func startAppUpdateSettingsObservation() {
        appUpdateSettingsObservation.acquire {
            sharedSnapshotObservation(
                \.appUpdateSettingsSubscription, into: \.appUpdateSettings,
                empty: AppUpdateSettingsSnapshot.companion.empty,
                observe: core.observeAppUpdateSettings)
        }
    }

    func stopAppUpdateSettingsObservation() {
        appUpdateSettingsObservation.release()
    }

    func setAppUpdateLevel(_ optionId: String) { core.setAppUpdateLevel(optionId: optionId) }

    func setAppUpdateDelay(_ optionId: String) { core.setAppUpdateDelay(optionId: optionId) }

    /// Only live while the licenses screen is on screen.
    private(set) var licenseList: LicenseListSnapshot = LicenseListSnapshot.companion.empty

    private(set) var localizationContributors: LocalizationContributorsSnapshot = LocalizationContributorsSnapshot
        .companion.empty

    @ObservationIgnored private let licenseObservation = SharedObservation()

    @ObservationIgnored private var licenseSubscription: BridgeObservation?

    @ObservationIgnored private let localizationContributorsObservation = SharedObservation()

    @ObservationIgnored private var localizationContributorsSubscription: BridgeObservation?

    func startLicenseObservation() {
        licenseObservation.acquire {
            sharedSnapshotObservation(
                \.licenseSubscription, into: \.licenseList, empty: LicenseListSnapshot.companion.empty,
                observe: core.observeLicense)
        }
    }

    func stopLicenseObservation() {
        licenseObservation.release()
    }

    func startLocalizationContributorsObservation() {
        localizationContributorsObservation.acquire {
            sharedSnapshotObservation(
                \.localizationContributorsSubscription, into: \.localizationContributors,
                empty: LocalizationContributorsSnapshot.companion.empty,
                observe: core.observeLocalizationContributors)
        }
    }

    func stopLocalizationContributorsObservation() {
        localizationContributorsObservation.release()
    }

    func loadAboutTeam() async -> AboutTeamSnapshot? {
        try? await core.loadAboutTeam()
    }

    func loadDataSafety() async -> [DataSafetyItemSnapshot] {
        (try? await core.loadDataSafety()) ?? []
    }
}

extension AppUpdateSnapshot {
    /// The release page or the store listing, or nil if there is nothing to open.
    var link: URL? { url.flatMap { URL(string: $0) } }
}
