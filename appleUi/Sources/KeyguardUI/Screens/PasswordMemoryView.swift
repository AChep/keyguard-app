import SwiftUI
import KeyguardShared

struct PasswordMemoryView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: L10n.passwordActionTestMemoryTitle,
            width: 420,
            height: 280,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.passwordMemory {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(L10n.passwordMemoryTestNote)
                        BridgedTextField(
                            label: L10n.password,
                            text: snapshot.value,
                            textRevision: snapshot.revision,
                            secure: true,
                            send: { dialogsModel.setPasswordMemoryText($0) },
                            style: .roundedBorder
                        )
                        if let error = snapshot.error {
                            Text(error).foregroundStyle(.red)
                        }
                    }
                    .padding(24)
                }
            }
        } actions: {
            Button(L10n.verify) { dialogsModel.verifyPasswordMemory() }
                .keyboardShortcut(.defaultAction)
                .disabled(!(dialogsModel.passwordMemory?.canVerify ?? false))
        }
    }
}
