import SwiftUI
import KeyguardShared

struct CliScreen: View {
    var body: some View {
        CliModelHost { model in
            CliView(model: model)
        }
        .navigationTitle(L10n.cliTitle)
    }
}

/// Owns a CLI settings session while the view is shown.
struct CliModelHost<Content: View>: View {
    @Environment(SessionFactory.self) private var sessions
    @State private var presentation = FormPresentation<CliModel>(stop: { $0.close() })
    @ViewBuilder let content: (CliModel) -> Content

    var body: some View {
        Group {
            if let model = presentation.model {
                content(model)
            } else {
                LoadingIndicator()
            }
        }
        .onAppear { presentation.start(makeModel: sessions.makeCliModel) }
        .onDisappear { presentation.close() }
    }
}

private struct CliView: View {
    let model: CliModel

    var body: some View {
        Form {
            if let snapshot = model.snapshot {
                // Builds that serve no command of the CLI have no CLI path.
                if let path = snapshot.path {
                    Section {
                        Toggle(
                            L10n.cliEnable,
                            isOn: Binding(
                                get: { snapshot.enabled },
                                set: { model.session.setEnabled(enabled: $0) }
                            )
                        )
                        .disabled(snapshot.busy)
                        Text(L10n.cliNote).foregroundStyle(.secondary)
                        if snapshot.enabled {
                            LabeledContent(L10n.cliUsage) {
                                Text(verbatim: snapshot.usage)
                                    .font(.system(.body, design: .monospaced)).textSelection(.enabled)
                            }
                            LabeledContent(L10n.cliPath) {
                                Text(path).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                            }
                            Button(L10n.copy) { model.session.doCopyPath() }
                        }
                    }
                }
                if snapshot.failed {
                    Text(L10n.errorFailedUnknown).foregroundStyle(.red)
                }
            } else {
                ProgressView()
            }
        }
        .formStyle(.grouped)
    }
}
