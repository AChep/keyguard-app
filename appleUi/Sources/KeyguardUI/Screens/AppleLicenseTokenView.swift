import SwiftUI

struct AppleLicenseTokenView: View {
    let title: String
    let token: String
    let status: String?
    @State private var revealed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            if status != nil { Text(L10n.prefItemLicenseKeyTokenStatusText(statusText)).font(.caption) }
            if revealed {
                Text(token).font(.caption.monospaced()).textSelection(.enabled)
            }
            HStack {
                Button(revealed ? L10n.prefItemLicenseKeyHideTokenAction : L10n.prefItemLicenseKeyShowTokenAction) {
                    revealed.toggle()
                }
                Button(L10n.prefItemLicenseKeyCopyTokenAction) { copyToken() }
            }
        }
        .onChange(of: token) { _, _ in revealed = false }
    }

    private var statusText: String {
        switch status {
        case "ACTIVE": L10n.prefItemLicenseKeyStatusActive
        case "GRACE": L10n.prefItemLicenseKeyStatusGrace
        case "EXPIRED": L10n.prefItemLicenseKeyStatusExpired
        case "REVOKED": L10n.prefItemLicenseKeyStatusRevoked
        case "REFUNDED": L10n.prefItemLicenseKeyStatusRefunded
        case "PENDING": L10n.prefItemLicenseKeyStatusPending
        case "INVALID": L10n.prefItemLicenseKeyStatusInvalid
        default: L10n.unknown
        }
    }

    private func copyToken() {
        #if os(iOS)
        UIPasteboard.general.setItems(
            [[UIPasteboard.typeAutomatic: token]],
            options: [.localOnly: true, .expirationDate: Date.now.addingTimeInterval(60)])
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(token, forType: .string)
        NSPasteboard.general.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        #endif
    }
}
