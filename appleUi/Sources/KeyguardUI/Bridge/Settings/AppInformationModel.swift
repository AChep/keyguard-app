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

    /// Only live while the licenses screen is on screen.
    private(set) var licenseList: LicenseListSnapshot = LicenseListSnapshot.companion.empty

    private(set) var localizationContributors: LocalizationContributorsSnapshot = LocalizationContributorsSnapshot
        .companion.empty

    /// In-memory app logs.
    private(set) var logs: LogsSnapshot = LogsSnapshot.companion.empty

    @ObservationIgnored private let licenseObservation = SharedObservation()

    @ObservationIgnored private var licenseSubscription: BridgeObservation?

    @ObservationIgnored private let localizationContributorsObservation = SharedObservation()

    @ObservationIgnored private var localizationContributorsSubscription: BridgeObservation?

    @ObservationIgnored private let logsObservation = SharedObservation()

    @ObservationIgnored private var logsSubscription: BridgeObservation?

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

    func startLogsObservation() {
        logsObservation.acquire {
            sharedSnapshotObservation(
                \.logsSubscription, into: \.logs, empty: LogsSnapshot.companion.empty,
                observe: core.observeLogs)
        }
    }

    func stopLogsObservation() {
        logsObservation.release()
    }

    func loadAboutTeam() async -> AboutTeamSnapshot? {
        try? await core.loadAboutTeam()
    }

    func loadDataSafety() async -> [DataSafetyItemSnapshot] {
        (try? await core.loadDataSafety()) ?? []
    }
}
