import SwiftUI
import KeyguardShared

/// Runs an edit on the presentation's key derivation form; nothing once the presentation moves on.
typealias MasterKdfPerform = @MainActor @Sendable (_ edit: (MasterKdfActions) -> Void) -> Void

/// The key derivation (Argon2id) fields, a test of the unlock time and a reset to the
/// defaults. Shared by the vault setup and the key derivation settings.
struct MasterKdfFields: View {
    let kdf: MasterKdfSnapshot
    let perform: MasterKdfPerform

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.masterkdfNote)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            field(kdf.iterations, title: L10n.masterkdfIterationsLabel) { $0.setIterations(text: $1) }
            field(kdf.memory, title: L10n.masterkdfMemoryLabel) { $0.setMemory(text: $1) }
            field(kdf.parallelism, title: L10n.masterkdfParallelismLabel) { $0.setParallelism(text: $1) }
            if let warning = kdf.warning {
                // Icon plus color, so the warning does not rely on color alone.
                Label {
                    Text(warning)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                // Bordered and borderless styles keep each button its own tap
                // target inside a form row.
                Button {
                    perform { $0.test() }
                } label: {
                    if kdf.isTesting {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Label(L10n.masterkdfTestButton, systemImage: "timer")
                    }
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(L10n.masterkdfTestButton)
                .disabled(!kdf.canTest)
                Spacer()
                Button(L10n.reset) {
                    perform { $0.reset() }
                }
                .buttonStyle(.borderless)
                .disabled(!kdf.canReset)
            }
            if let result = kdf.testResultText {
                Text(result)
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func field(
        _ field: TextFieldSnapshot,
        title: String,
        set: @escaping (MasterKdfActions, String) -> Void
    ) -> KeyguardFormField {
        KeyguardFormField(
            field: field,
            title: title,
            numeric: true,
            send: { [perform] text in perform { set($0, text) } }
        )
    }
}
