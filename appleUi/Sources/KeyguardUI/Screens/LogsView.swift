import SwiftUI
import KeyguardShared
#if os(macOS)
import AppKit
#endif

struct LogsView: View {
    @Environment(AppInformationModel.self) private var appInformationModel
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor

    private var snapshot: LogsSnapshot { appInformationModel.logs }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.logsEmptyTitle, systemImage: "doc.text.magnifyingglass")
            } description: {
                Text(L10n.logsEmptyNote)
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
            // Match the sibling read-only data lists (LicenseView /
            // LocalizationContributorsView) so dense monospaced log rows get
            // macOS row striping for separation.
            #if os(macOS)
            .alternatingRowBackgrounds()
            #endif
        }
        .navigationTitle(L10n.logsHeaderTitle)
        .observing(
            start: { appInformationModel.startLogsObservation() },
            stop: { appInformationModel.stopLogsObservation() }
        )
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
        // text selection alone is the only existing copy path.
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
