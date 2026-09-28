import SwiftUI
import KeyguardShared

struct AppleLicenseSection: View {
    @Environment(SubscriptionsModel.self) private var subscriptionsModel
    let snapshot: AppleLicenseSnapshot
    let storeAvailable: Bool
    @State private var token = ""

    var body: some View {
        Section {
            if snapshot.unlocked {
                if storeAvailable {
                    Button(L10n.prefItemLicenseSyncTitle) { subscriptionsModel.syncAppleLicense() }
                }
                if let key = snapshot.claimedKey {
                    AppleLicenseTokenView(
                        title: L10n.prefItemLicenseKeyPurchaseTokenTitle, token: key, status: snapshot.claimedStatus)
                }
                if let key = snapshot.linkedKey {
                    AppleLicenseTokenView(
                        title: L10n.prefItemLicenseKeyLinkedTokenTitle, token: key, status: snapshot.linkedStatus)
                    Button(L10n.prefItemLicenseKeyRefreshAction) { subscriptionsModel.refreshAppleLicense() }
                    Button(L10n.prefItemLicenseKeyRemoveAction, role: .destructive) {
                        subscriptionsModel.removeAppleLicense()
                    }
                }
                SecureField(L10n.prefItemLicenseKeyFieldLabel, text: $token)
                    .autocorrectionDisabled()
                    #if os(iOS)
                .textInputAutocapitalization(.never)
                    #endif
                Button(L10n.prefItemLicenseKeyLinkAction) {
                    subscriptionsModel.linkAppleLicense(token)
                    token = ""
                }
                .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if snapshot.busy { ProgressView() }
                if let message = snapshot.message { Text(message) }
            } else {
                Text(L10n.prefItemLicenseKeyUnlockNote)
            }
        }
        .disabled(snapshot.busy)
        .onChange(of: snapshot.unlocked) { _, unlocked in
            if !unlocked { token = "" }
        }
    }
}
