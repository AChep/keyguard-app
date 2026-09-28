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

    @ObservationIgnored private var appInformationSubscription: BridgeObservation?

    static func formatAppVersion(version: String?, build: String?) -> String {
        guard let version, !version.isEmpty else { return L10n.unknown }
        guard let build, !build.isEmpty else { return version }
        return "\(version) (\(build))"
    }

    func startAppInformationObservation() {
        startObservation(
            \.appInformationSubscription, into: \.appInformation, observe: core.observeAppInformation)
    }

    func stopAppInformationObservation() {
        stopObservation(
            \.appInformationSubscription, resetting: \.appInformation, to: AppInformationSnapshot.companion.empty)
    }

    /// Open-source licenses list (app-global), produced by the shared Kotlin
    /// `licenseStateProducer` running headless inside `KeyguardCore`. Only live
    /// while the licenses screen is on screen.
    private(set) var licenseList: LicenseListSnapshot = LicenseListSnapshot.companion.empty

    /// Localization contributors list (app-global), produced by the shared Kotlin
    /// localization contributors producer running headless inside `KeyguardCore`.
    private(set) var localizationContributors: LocalizationContributorsSnapshot = LocalizationContributorsSnapshot
        .companion.empty

    /// In-memory app logs (app-global), produced by the shared Kotlin
    /// `logsStateProducer` running headless inside `KeyguardCore`.
    private(set) var logs: LogsSnapshot = LogsSnapshot.companion.empty

    @ObservationIgnored private var licenseSubscription: BridgeObservation?

    @ObservationIgnored private var localizationContributorsSubscription: BridgeObservation?

    @ObservationIgnored private var logsSubscription: BridgeObservation?

    func startLicenseObservation() {
        startObservation(\.licenseSubscription, into: \.licenseList, observe: core.observeLicense)
    }

    func stopLicenseObservation() {
        stopObservation(\.licenseSubscription, resetting: \.licenseList, to: LicenseListSnapshot.companion.empty)
    }

    func startLocalizationContributorsObservation() {
        startObservation(
            \.localizationContributorsSubscription, into: \.localizationContributors,
            observe: core.observeLocalizationContributors)
    }

    func stopLocalizationContributorsObservation() {
        stopObservation(
            \.localizationContributorsSubscription, resetting: \.localizationContributors,
            to: LocalizationContributorsSnapshot.companion.empty)
    }

    func startLogsObservation() {
        startObservation(\.logsSubscription, into: \.logs, observe: core.observeLogs)
    }

    func stopLogsObservation() {
        stopObservation(\.logsSubscription, resetting: \.logs, to: LogsSnapshot.companion.empty)
    }

    /// Loads the static About-the-team content (localized bio + social links).
    func loadAboutTeam() async -> AboutTeamSnapshot? {
        try? await core.loadAboutTeam()
    }

    /// Loads the static Data Safety content (localized structured document).
    func loadDataSafety() async -> [DataSafetyItemSnapshot] {
        (try? await core.loadDataSafety()) ?? []
    }
}
