import SwiftUI
import KeyguardShared

struct EnvironmentsMcpScreen: View {
    var body: some View {
        EnvironmentsModelHost(title: L10n.protocolMcp) { model in
            EnvironmentsMcpView(model: model)
        }
    }
}

private struct EnvironmentsMcpView: View {
    let model: EnvironmentsModel

    var body: some View {
        Form {
            if let snapshot = model.snapshot {
                Section {
                    Toggle(
                        L10n.environmentsMcpEnable,
                        isOn: Binding(
                            get: { snapshot.mcpEnabled },
                            set: { model.session.setMcpEnabled(enabled: $0) }
                        )
                    )
                    Text(L10n.environmentsMcpNote).foregroundStyle(.secondary)
                }
                if !snapshot.mcpConfiguration.isEmpty {
                    Section(L10n.environmentsMcpSetup) {
                        Text(snapshot.mcpConfiguration).font(.system(.body, design: .monospaced)).textSelection(
                            .enabled)
                        Button(L10n.copy) { model.session.doCopyMcpConfiguration() }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .formStyle(.grouped)
    }
}
