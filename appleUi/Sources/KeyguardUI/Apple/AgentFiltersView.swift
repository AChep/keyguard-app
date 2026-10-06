#if os(macOS)
import SwiftUI
import KeyguardShared

/// Restricts which keys the SSH or GPG agent serves, mirroring the common
/// agent filter screens. Save does not dismiss: the producer pops itself,
/// which dismisses the sheet via the bridge `onClose` callback.
struct AgentFiltersView: View {
    @State private var model: DetailSessionModel<Bool, AgentFiltersSnapshot, AgentFiltersSession>
    let title: String
    let lockedText: String
    var note: String? = nil

    init(
        title: String, lockedText: String, note: String? = nil,
        makeSession: @escaping () -> AgentFiltersSession
    ) {
        self.title = title
        self.lockedText = lockedText
        self.note = note
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribeWithCompletion: { BridgeObservation($0.observe(onChange: $1, onClose: $2)) }
            ))
    }

    var body: some View {
        DetailSessionContent(
            model: model, initialSnapshot: AgentFiltersSnapshot.companion.empty, dismissOnComplete: true
        ) { snapshot, perform in
            AgentFiltersContent(
                s: snapshot,
                title: title,
                lockedText: lockedText,
                note: note,
                invoke: { id in perform { $0.invokeFilter(id: id) } },
                reset: { perform { $0.reset() } },
                save: { perform { $0.save() } }
            )
        }
        .frame(width: 520, height: 560)
    }
}

private struct AgentFiltersContent: View {
    @Environment(\.dismiss) private var dismiss
    let s: AgentFiltersSnapshot
    let title: String
    let lockedText: String
    let note: String?
    let invoke: (String) -> Void
    let reset: () -> Void
    let save: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
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
                        invoke: invoke,
                        clear: reset
                    )
                } else {
                    ContentUnavailableView {
                        Label(title, systemImage: "line.3.horizontal.decrease.circle")
                    } description: {
                        Text(lockedText)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            HStack {
                if let note {
                    Text(note)
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button(L10n.cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(L10n.save, action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!s.canSave)
            }
            .padding(16)
        }
    }
}

#endif
