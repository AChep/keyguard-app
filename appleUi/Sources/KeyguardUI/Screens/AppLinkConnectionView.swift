import SwiftUI
import KeyguardShared

/// The relay this device reaches its linked apps through, the ICE servers and the local
/// network. The same for every link, and set before the first one. Saving closes it.
struct AppLinkConnectionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model: AppLinkConnectionModel

    init(makeSource: @escaping () -> any AppLinkConnectionSource) {
        _model = State(wrappedValue: AppLinkConnectionModel(makeSource: makeSource))
    }

    private var snapshot: AppLinkConnectionSnapshot { model.snapshot }

    var body: some View {
        Group {
            if snapshot.loaded {
                SettingsForm {
                    Section {
                        KeyguardNetworkFields(editor: model, fieldsDisabled: snapshot.isSaving)
                    }
                    AppLinkInfoSection(notes: AppLinkInfoSection.relay)
                }
            } else {
                LoadingIndicator()
            }
        }
        .navigationTitle(L10n.appLinkConnectionTitle)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if snapshot.isSaving {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button(L10n.save, action: model.save)
                        .disabled(!snapshot.canSave)
                }
            }
        }
        .observing(start: model.start, stop: model.stop)
        // Saved, or the vault locked.
        .onChange(of: model.didComplete) { _, completed in
            if completed { dismiss() }
        }
    }
}
