import SwiftUI
import KeyguardShared

struct LargeTypeView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        ModalSheet(
            title: dialogsModel.largeType?.title ?? L10n.largetypeTitle,
            width: 420,
            height: 320,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.largeType {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
        .keepScreenAwake(requiresPreference: false)
    }

    @ViewBuilder
    private func content(_ snapshot: LargeTypeSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(snapshot.groups.enumerated()), id: \.offset) { _, group in
                        FlowLayout(spacing: 4) {
                            ForEach(group, id: \.index) { symbol in
                                // A real Button restores the .isButton trait,
                                // VoiceOver activation, and macOS keyboard focus
                                // that a bare .onTapGesture omits.
                                Button {
                                    dialogsModel.selectLargeTypeSymbol(index: Int(symbol.index))
                                } label: {
                                    SymbolTile(
                                        symbol: symbol,
                                        selected: symbol.index <= snapshot.selectedIndex
                                    )
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(
                                    L10n.largetypeCharacterIndexLabel(Int(symbol.index) + 1, symbol.text)
                                )
                                .accessibilityAddTraits(symbol.index <= snapshot.selectedIndex ? [.isSelected] : [])
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let note = snapshot.note, !note.isEmpty {
                Text(note)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(24)
    }
}

private struct SymbolTile: View {
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor
    @ScaledMetric(relativeTo: .largeTitle) private var symbolSize = 34.0
    let symbol: LargeTypeSymbolSnapshot
    let selected: Bool

    var body: some View {
        VStack(spacing: 4) {
            Text(symbol.text)
                .font(.system(size: symbolSize, weight: .semibold, design: .monospaced))
                .foregroundStyle(symbolColor)
            Text("\(Int(symbol.index) + 1)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(minWidth: 44, minHeight: 44)
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(selected ? Color.accentColor.opacity(0.3) : Color.secondary.opacity(0.08))
        )
        .overlay {
            if selected && differentiateWithoutColor {
                RoundedRectangle(cornerRadius: 6)
                    .strokeBorder(.primary, lineWidth: 2)
            }
        }
        .contentShape(Rectangle())
    }

    private var symbolColor: Color {
        // When the user asks to differentiate without color, drop the digit/symbol
        // color cue (the literal character + 1-based index already convey content).
        if differentiateWithoutColor { return .primary }
        switch symbol.color {
        case .digit: return PasswordCharacterStyle.digit.color ?? .primary
        case .symbol: return PasswordCharacterStyle.symbol.color ?? .primary
        default: return .primary
        }
    }
}
