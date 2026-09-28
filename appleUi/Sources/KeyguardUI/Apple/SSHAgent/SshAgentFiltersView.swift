#if os(macOS)
import SwiftUI
import KeyguardShared

/// Restricts which SSH keys the agent serves, mirroring the common
/// `SshAgentFiltersScreen`. The filter tree is produced by the shared
/// `sshAgentFiltersStateProducer` running headless inside `KeyguardCore` and
/// rendered with the same `FilterSidebar` used by the vault list. Presented as
/// a modal sheet in the standard header/content/footer layout: saving persists
/// the filter; the producer then pops itself, which dismisses the sheet via the
/// bridge `onClose` callback.
struct SshAgentFiltersView: View {
    @Environment(SshAgentModel.self) private var sshAgentModel
    @Environment(\.dismiss) private var dismiss

    private var s: SshAgentFiltersSnapshot { sshAgentModel.sshAgentFilters }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L10n.sshAgentFiltersHeaderTitle)
                    .font(.headline)
                Spacer()
            }
            .padding(16)
            Divider()
            Group {
                if s.loaded {
                    FilterSidebar(
                        filters: s.items,
                        count: Int(s.count),
                        canClearFilters: s.canReset,
                        invoke: { id in sshAgentModel.invokeSshAgentFilter(id: id) },
                        clear: { sshAgentModel.resetSshAgentFilters() }
                    )
                } else {
                    ContentUnavailableView {
                        Label(L10n.sshAgentFiltersHeaderTitle, systemImage: "line.3.horizontal.decrease.circle")
                    } description: {
                        Text(L10n.sshAgentFiltersLockedText)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            HStack {
                Spacer()
                Button(L10n.cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(L10n.save) { sshAgentModel.saveSshAgentFilters() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!s.canSave)
            }
            .padding(16)
        }
        .frame(width: 520, height: 560)
        .onAppear {
            sshAgentModel.startSshAgentFiltersObservation { dismiss() }
        }
        .onDisappear { sshAgentModel.stopSshAgentFiltersObservation() }
    }
}

#endif
