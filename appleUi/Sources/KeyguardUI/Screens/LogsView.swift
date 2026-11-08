import SwiftUI
import KeyguardShared
#if os(macOS)
import AppKit
#endif

struct LogsView: View {
    @State private var model: DetailSessionModel<Bool, LogsSnapshot, LogsSession>
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    init(makeSession: @escaping () -> LogsSession) {
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) { snapshot, perform in
            content(snapshot, perform: perform)
        }
        .navigationTitle(L10n.logsHeaderTitle)
    }

    private func content(_ snapshot: LogsSnapshot, perform: SessionActions<LogsSession>) -> some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.logsEmptyTitle, systemImage: "doc.text.magnifyingglass")
            } description: {
                Text(L10n.logsEmptyNote)
            } actions: {
                if !snapshot.recording {
                    recordingButton(snapshot, perform: perform)
                        .buttonStyle(.borderedProminent)
                }
            }
        } content: {
            List {
                ForEach(snapshot.items, id: \.id) { item in
                    if item.kind == LogsItemKind.section {
                        Text(item.text)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)
                    } else {
                        row(item)
                    }
                }
            }
            // Row striping separates the dense monospaced log rows, as in the other
            // read-only data lists.
            #if os(macOS)
            .alternatingRowBackgrounds()
            #endif
        }
        .toolbar {
            if snapshot.loaded {
                ToolbarItem(placement: .primaryAction) {
                    recordingButton(snapshot, perform: perform)
                }
                ToolbarItem {
                    Button {
                        perform { $0.export() }
                    } label: {
                        Label(L10n.logsExportButton, systemImage: "square.and.arrow.down")
                    }
                    .disabled(!snapshot.canExport)
                }
            }
        }
    }

    /// Starts or stops keeping new logs in memory.
    private func recordingButton(_ snapshot: LogsSnapshot, perform: SessionActions<LogsSession>) -> some View {
        Button {
            perform { $0.toggleRecording() }
        } label: {
            if snapshot.recording {
                Label(L10n.logsStopRecordingFabTitle, systemImage: "stop.circle")
            } else {
                Label(L10n.logsStartRecordingFabTitle, systemImage: "record.circle")
            }
        }
        .disabled(!snapshot.canToggleRecording)
    }

    private func row(_ item: LogsItemSnapshot) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Group {
                if differentiateWithoutColor {
                    Image(systemName: levelSymbol(item.level))
                        .font(.caption)
                        .foregroundStyle(levelColor(item.level))
                        .frame(width: 16)
                } else {
                    Circle()
                        .fill(levelColor(item.level))
                        .frame(width: 8, height: 8)
                        .padding(.top, 5)
                }
            }
            .accessibilityLabel(Text(verbatim: item.level ?? ""))
            .accessibilityHidden(item.level == nil)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.text)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                if let time = item.time, !time.isEmpty {
                    Text(time)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 1)
        // macOS pointer users expect a right-click Copy for diagnostic content;
        // otherwise text selection is the only copy path.
        #if os(macOS)
        .contextMenu {
            Button(L10n.copy) {
                let pasteboard = NSPasteboard.general
                pasteboard.clearContents()
                pasteboard.setString(item.text, forType: .string)
            }
        }
        #endif
    }

    /// Maps the uppercase `LogLevel` name to a severity colour.
    private func levelColor(_ level: String?) -> Color {
        switch level {
        case "VERBOSE", "DEBUG": return .secondary
        case "INFO": return .blue
        case "WARNING": return .orange
        case "ERROR", "WTF": return .red
        default: return .secondary
        }
    }

    private func levelSymbol(_ level: String?) -> String {
        switch level {
        case "VERBOSE": return "ellipsis.circle"
        case "DEBUG": return "ladybug"
        case "INFO": return "info.circle"
        case "WARNING": return "exclamationmark.triangle"
        case "ERROR", "WTF": return "xmark.octagon"
        default: return "circle"
        }
    }
}
