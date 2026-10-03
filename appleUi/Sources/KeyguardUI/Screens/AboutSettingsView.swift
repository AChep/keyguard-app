import SwiftUI
import KeyguardShared

struct AboutSettingsView: View {
    @Environment(SessionFactory.self) private var sessions
    @Environment(AppInformationModel.self) private var appInformationModel
    @Environment(AppPreferencesModel.self) private var preferencesModel
    let item: SettingsItemSnapshot

    private enum Dialog: String, Identifiable {
        case feedback, team, licenses, localization, dataSafety
        var id: String { rawValue }
    }

    @State private var dialog: Dialog?

    var body: some View {
        SettingsForm(ready: appInformationModel.appInformation.loaded) {
            Section {
                Button {
                    dialog = .feedback
                } label: {
                    Label(L10n.contactusHeaderTitle, systemImage: "envelope")
                }
                .settingsSearchTarget(.feedback)
                Button {
                    dialog = .team
                } label: {
                    Label(L10n.prefItemAppTeamTitle, systemImage: "person.2")
                }
                .settingsSearchTarget(.team)
                Link(destination: URL(string: KeyguardUrls.shared.REDDIT)!) {
                    Label(L10n.prefItemRedditCommunityTitle, systemImage: "bubble.left.and.bubble.right")
                }
                .settingsSearchTarget(.community)
                Link(destination: URL(string: KeyguardUrls.shared.GITHUB)!) {
                    Label(L10n.prefItemGithubTitle, systemImage: "chevron.left.forwardslash.chevron.right")
                }
                .settingsSearchTarget(.source)
            }
            Section {
                Button {
                    dialog = .licenses
                } label: {
                    Label(L10n.prefItemOpenSourceLicensesTitle, systemImage: "doc.plaintext")
                }
                .settingsSearchTarget(.licenses)
                Button {
                    dialog = .localization
                } label: {
                    Label(L10n.settingsLocalizationHeaderTitle, systemImage: "character.bubble")
                }
                .settingsSearchTarget(.translations)
                Button {
                    dialog = .dataSafety
                } label: {
                    Label(L10n.prefItemDataSafetyTitle, systemImage: "lock.shield")
                }
                .settingsSearchTarget(.dataSafety)
                Link(destination: URL(string: KeyguardUrls.shared.PRIVACY_POLICY)!) {
                    Label(L10n.prefItemPrivacyPolicyTitle, systemImage: "hand.raised")
                }
                .settingsSearchTarget(.privacyPolicy)
            }
            Section(L10n.settingsDiagnosticsHeaderTitle) {
                NavigationLink {
                    LogsView()
                } label: {
                    Label(L10n.logsHeaderTitle, systemImage: "doc.text.magnifyingglass")
                }
                .settingsSearchTarget(.logs)
                NavigationLink {
                    UrlRuleListScreen(
                        title: L10n.prefItemUrlOverrideTitle,
                        emptyText: L10n.urloverrideListEmptyText,
                        subtitleLabel: L10n.regex,
                        detailLabel: L10n.command,
                        makeSession: sessions.makeUrlOverrideListSession
                    )
                } label: {
                    Label(L10n.prefItemUrlOverrideTitle, systemImage: "arrow.triangle.branch")
                }
                .settingsSearchTarget(.urlRules)
                Toggle(
                    L10n.prefItemWebdavTransactionsTitle,
                    isOn: Binding(
                        get: { preferencesModel.appPreferences.webDavTransactions },
                        set: { preferencesModel.setWebDavTransactions($0) }
                    )
                )
                .disabled(!preferencesModel.appPreferences.loaded)
                .settingsSearchTarget(.webdavTransactions)
            }
            Section {
                LabeledContent(L10n.prefItemAppVersionTitle, value: appInformationModel.appVersion)
                    .settingsSearchTarget(.appVersion)
                if !appInformationModel.appInformation.buildDate.isEmpty {
                    LabeledContent(
                        L10n.prefItemAppBuildDateTitle,
                        value: appInformationModel.appInformation.buildDate
                    )
                    .settingsSearchTarget(.buildDate)
                }
                if let ref = appInformationModel.appInformation.buildRef,
                    let urlString = appInformationModel.appInformation.buildRefUrl,
                    let url = URL(string: urlString)
                {
                    Link(destination: url) {
                        LabeledContent(L10n.prefItemAppBuildRefTitle) {
                            Text(ref)
                                .multilineTextAlignment(.trailing)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .settingsSearchTarget(.buildRef)
                }
                if let text = appInformationModel.appInformation.changelogText,
                    let urlString = appInformationModel.appInformation.changelogUrl,
                    let url = URL(string: urlString)
                {
                    Link(destination: url) {
                        LabeledContent(L10n.prefItemAppChangelogTitle) {
                            Text(text)
                                .multilineTextAlignment(.trailing)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .settingsSearchTarget(.changelog)
                }
            }
        }
        .navigationTitle(item.title)
        .observing(
            start: { appInformationModel.startAppInformationObservation() },
            stop: { appInformationModel.stopAppInformationObservation() }
        )
        .sheet(item: $dialog) { dialog in
            switch dialog {
            case .feedback:
                FeedbackSheet(makeSession: sessions.makeFeedbackSession)
            case .team:
                ModalSheet(title: L10n.settingsTeamHeaderTitle) {
                    AboutTeamView()
                }
            case .licenses:
                ModalSheet(title: L10n.settingsOpenSourceLicensesHeaderTitle) {
                    LicenseView()
                }
            case .localization:
                ModalSheet(title: L10n.settingsLocalizationHeaderTitle) {
                    LocalizationContributorsView()
                }
            case .dataSafety:
                ModalSheet(title: L10n.datasafetyHeaderTitle) {
                    DataSafetyView()
                }
            }
        }
    }
}
