import SwiftUI
import KeyguardShared

struct AboutSettingsView: View {
    @Environment(AppInformationModel.self) private var appInformationModel
    @Environment(UrlRulesModel.self) private var urlRulesModel
    let item: SettingsItemSnapshot

    private enum Dialog: String, Identifiable {
        case feedback, team, licenses, localization, dataSafety
        var id: String { rawValue }
    }

    @State private var dialog: Dialog?

    var body: some View {
        // Grouped Form to match every sibling sub-route (Security/Display/Watchtower/
        // AutoFill/Developer/Backups), keeping consistent row insets and background.
        Form {
            Section {
                Button {
                    dialog = .feedback
                } label: {
                    Label(L10n.contactusHeaderTitle, systemImage: "envelope")
                }
                Button {
                    dialog = .team
                } label: {
                    Label(L10n.prefItemAppTeamTitle, systemImage: "person.2")
                }
                Link(destination: URL(string: "https://www.reddit.com/r/keyguard/")!) {
                    Label(L10n.prefItemRedditCommunityTitle, systemImage: "bubble.left.and.bubble.right")
                }
                Link(destination: URL(string: "https://github.com/AChep/keyguard-app/")!) {
                    Label(L10n.prefItemGithubTitle, systemImage: "chevron.left.forwardslash.chevron.right")
                }
            }
            Section {
                Button {
                    dialog = .licenses
                } label: {
                    Label(L10n.prefItemOpenSourceLicensesTitle, systemImage: "doc.plaintext")
                }
                Button {
                    dialog = .localization
                } label: {
                    Label(L10n.settingsLocalizationHeaderTitle, systemImage: "character.bubble")
                }
                Button {
                    dialog = .dataSafety
                } label: {
                    Label(L10n.prefItemDataSafetyTitle, systemImage: "lock.shield")
                }
                Link(destination: URL(string: "https://gist.github.com/AChep/1fd4e019a4ad8f9647ba3b4694b5dc1c")!) {
                    Label(L10n.prefItemPrivacyPolicyTitle, systemImage: "hand.raised")
                }
            }
            Section(L10n.settingsDiagnosticsHeaderTitle) {
                NavigationLink {
                    LogsView()
                } label: {
                    Label(L10n.logsHeaderTitle, systemImage: "doc.text.magnifyingglass")
                }
                NavigationLink {
                    UrlRuleListView(
                        title: L10n.prefItemUrlOverrideTitle,
                        emptyText: L10n.urloverrideListEmptyText,
                        subtitleLabel: L10n.regex,
                        detailLabel: L10n.command,
                        snapshotKeyPath: \.urlOverrideList,
                        start: { urlRulesModel.startUrlOverrideListObservation() },
                        stop: { urlRulesModel.stopUrlOverrideListObservation() },
                        onNew: { urlRulesModel.invokeUrlOverrideListPrimaryAction() },
                        invokeItemAction: { urlRulesModel.invokeUrlOverrideListItemAction(id: $0) },
                        invokeSelectionAction: { urlRulesModel.invokeUrlOverrideListSelectionAction(id: $0) },
                        toggleSelection: { urlRulesModel.toggleUrlOverrideListSelection(id: $0) },
                        clearSelection: { urlRulesModel.clearUrlOverrideListSelection() }
                    )
                } label: {
                    Label(L10n.prefItemUrlOverrideTitle, systemImage: "arrow.triangle.branch")
                }
            }
            Section {
                LabeledContent(L10n.prefItemAppVersionTitle, value: appInformationModel.appVersion)
                if !appInformationModel.appInformation.buildDate.isEmpty {
                    LabeledContent(
                        L10n.prefItemAppBuildDateTitle,
                        value: appInformationModel.appInformation.buildDate)
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
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(item.title)
        .observing(
            start: { appInformationModel.startAppInformationObservation() },
            stop: { appInformationModel.stopAppInformationObservation() }
        )
        .sheet(item: $dialog) { dialog in
            switch dialog {
            case .feedback:
                FeedbackSheet()
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
