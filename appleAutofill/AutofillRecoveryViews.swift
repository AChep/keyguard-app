import SwiftUI
import KeyguardShared

struct AutofillErrorView: View {
    let message: String
    var onRetry: (() -> Void)? = nil
    let onCancel: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(L("error_failed_unknown"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            if let onRetry { Button(L("retry"), action: onRetry) }
            Button(L("cancel"), action: onCancel)
        }
        .frame(minWidth: 300, minHeight: 250)
    }
}

/// The user chooses the exact credential, including when several share an RP/user.
struct AutofillPasskeyListView: View {
    let identities: [PasskeyIdentitySnapshot]
    let onPick: (PasskeyIdentitySnapshot) -> Void
    let onCancel: () -> Void
    @State private var isPicking = false

    var body: some View {
        NavigationStack {
            List(identities, id: \.recordId) { identity in
                Button {
                    guard !isPicking else { return }
                    isPicking = true
                    onPick(identity)
                } label: {
                    VStack(alignment: .leading) {
                        Text(identity.userName.isEmpty ? identity.rpId : identity.userName)
                        Text(identity.cipherName.isEmpty ? identity.rpId : identity.cipherName)
                            .font(.caption).foregroundStyle(.secondary)
                        if !identity.accountName.isEmpty {
                            Text(identity.accountName).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .disabled(isPicking)
            }
            .navigationTitle(L("autofill_choose_passkey"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel"), action: onCancel)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 360)
        #endif
    }
}
