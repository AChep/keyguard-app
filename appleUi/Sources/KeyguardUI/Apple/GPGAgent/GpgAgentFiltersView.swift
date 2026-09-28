#if os(macOS)
import SwiftUI
import KeyguardShared

/// Restricts which GPG keys the agent serves, mirroring the common
/// `GpgAgentFiltersScreen`. The filter tree is produced by the shared
/// `gpgAgentFiltersStateProducer` running headless inside `KeyguardCore` and
/// rendered with the same `FilterSidebar` used by the vault list. Presented as
/// a modal sheet in the standard header/content/footer layout: saving persists
/// the filter; the producer then pops itself, which dismisses the sheet via the
/// bridge `onClose` callback.
struct GpgAgentFiltersView: View {
    @Environment(GpgAgentModel.self) private var gpgAgentModel
    @Environment(\.dismiss) private var dismiss

    @State private var observationID: UUID?

    private var s: GpgAgentFiltersSnapshot { gpgAgentModel.gpgAgentFilters }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L10n.gpgAgentFiltersHeaderTitle)
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
                        invoke: { id in gpgAgentModel.invokeGpgAgentFilter(id: id) },
                        clear: { gpgAgentModel.resetGpgAgentFilters() }
                    )
                } else {
                    ContentUnavailableView {
                        Label(L10n.gpgAgentFiltersHeaderTitle, systemImage: "line.3.horizontal.decrease.circle")
                    } description: {
                        Text(L10n.sshAgentHistoryResponseVaultLocked)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            HStack {
                Text(L10n.gpgAgentFiltersNoteSaveToApply)
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(L10n.cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(L10n.save) { gpgAgentModel.saveGpgAgentFilters() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!s.canSave)
            }
            .padding(16)
        }
        .frame(width: 520, height: 560)
        .onAppear {
            guard observationID == nil else { return }
            observationID = gpgAgentModel.startGpgAgentFiltersObservation { dismiss() }
        }
        .onDisappear {
            if let observationID { gpgAgentModel.stopGpgAgentFiltersObservation(id: observationID) }
            observationID = nil
        }
    }
}

#endif
