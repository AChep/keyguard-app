import SwiftUI

enum AddAccountKind: String, Identifiable, Hashable {
    case bitwarden
    case keepass

    var id: String { rawValue }
}

struct AddAccountMenuItems: View {
    let select: (AddAccountKind) -> Void

    var body: some View {
        Button {
            select(.bitwarden)
        } label: {
            Label("Bitwarden", systemImage: "cloud")
            Text(L10n.addaccountDescriptionShortBitwardenText)
        }
        Button {
            select(.keepass)
        } label: {
            Label("KeePass (\(L10n.readyStatusBeta))", systemImage: "doc.badge.ellipsis")
            Text(L10n.addaccountDescriptionShortKeepassText)
        }
    }
}

struct AddAccountDestination: View {
    let kind: AddAccountKind

    var body: some View {
        switch kind {
        case .bitwarden:
            BitwardenLoginView()
        case .keepass:
            KeePassLoginView()
        }
    }
}
