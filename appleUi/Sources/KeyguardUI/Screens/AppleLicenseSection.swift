import SwiftUI
import KeyguardShared

struct AppleLicenseSection: View {
    @Environment(SubscriptionsModel.self) private var subscriptionsModel
    let snapshot: AppleLicenseSnapshot
    let storeAvailable: Bool
    @State private var token = ""

    /// Resolve directly to a visible row; revealing must not unlock or link a license.
    static func searchAliases(
        _ snapshot: AppleLicenseSnapshot, storeAvailable: Bool
    ) -> [SettingsSearchTarget: SettingsSearchTarget] {
        var aliases: [SettingsSearchTarget: SettingsSearchTarget] = [:]
        if !snapshot.unlocked {
            aliases[.licenseLink] = .licenseEntry
        }
        if !snapshot.unlocked || !storeAvailable {
            aliases[.licenseSync] = .licenseEntry
        }
        if !snapshot.unlocked || snapshot.claimedKey == nil {
            aliases[.licensePurchaseToken] = snapshot.unlocked && storeAvailable ? .licenseSync : .licenseEntry
        }
        if !snapshot.unlocked || snapshot.linkedKey == nil {
            aliases[.licenseLinkedToken] = .licenseEntry
            aliases[.licenseRefresh] = .licenseEntry
            aliases[.licenseRemove] = .licenseEntry
        }
        return aliases
    }

    var body: some View {
        Section {
            if snapshot.unlocked {
                if storeAvailable {
                    Button(L10n.prefItemLicenseSyncTitle) { subscriptionsModel.syncAppleLicense() }
                        .settingsSearchTarget(.licenseSync)
                }
                if let key = snapshot.claimedKey {
                    AppleLicenseTokenView(
                        title: L10n.prefItemLicenseKeyPurchaseTokenTitle, token: key, status: snapshot.claimedStatus
                    )
                    .settingsSearchTarget(.licensePurchaseToken)
                }
                if let key = snapshot.linkedKey {
                    AppleLicenseTokenView(
                        title: L10n.prefItemLicenseKeyLinkedTokenTitle, token: key, status: snapshot.linkedStatus
                    )
                    .settingsSearchTarget(.licenseLinkedToken)
                    Button(L10n.prefItemLicenseKeyRefreshAction) { subscriptionsModel.refreshAppleLicense() }
                        .settingsSearchTarget(.licenseRefresh)
                    Button(L10n.prefItemLicenseKeyRemoveAction, role: .destructive) {
                        subscriptionsModel.removeAppleLicense()
                    }
                    .settingsSearchTarget(.licenseRemove)
                }
            }
            Group {
                if snapshot.unlocked {
                    SecureField(L10n.prefItemLicenseKeyFieldLabel, text: $token)
                        .autocorrectionDisabled()
                        #if os(iOS)
                    .textInputAutocapitalization(.never)
                        #endif
                } else {
                    Text(L10n.prefItemLicenseKeyUnlockNote)
                }
            }
            .settingsSearchTarget(.licenseEntry)
            if snapshot.unlocked {
                Button(L10n.prefItemLicenseKeyLinkAction) {
                    subscriptionsModel.linkAppleLicense(token)
                    token = ""
                }
                .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .settingsSearchTarget(.licenseLink)
                if snapshot.busy { ProgressView() }
                if let message = snapshot.message { Text(message) }
            }
        }
        .disabled(snapshot.busy)
        .onChange(of: snapshot.unlocked) { _, unlocked in
            if !unlocked { token = "" }
        }
    }
}
