import SwiftUI
import KeyguardShared

struct ColorPickerView: View {
    @Environment(DialogsModel.self) private var dialogsModel
    @Environment(\.colorScheme) private var colorScheme

    private let columns = [GridItem(.adaptive(minimum: 44), spacing: 12)]

    var body: some View {
        ModalSheet(
            title: dialogsModel.colorPicker?.title ?? L10n.colorpickerTitle,
            width: 460,
            height: 360,
            detents: [.medium],
            dismissLabel: L10n.close
        ) {
            if let snapshot = dialogsModel.colorPicker {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            Button(L10n.ok) {
                dialogsModel.confirmColorPicker()
            }
            .keyboardShortcut(.defaultAction)
            .disabled(!(dialogsModel.colorPicker?.confirmEnabled ?? false))
        }
    }

    @ViewBuilder
    private func content(_ snapshot: ColorPickerSnapshot) -> some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(snapshot.items, id: \.id) { item in
                    swatch(item, selected: item.index == snapshot.selectedIndex)
                }
            }
            .padding(24)
        }
    }

    private func swatch(_ item: ColorSwatchSnapshot, selected: Bool) -> some View {
        let argb = colorScheme == .dark ? item.argbDark : item.argbLight
        let color = swatchColor(argb)
        return Button {
            dialogsModel.selectColorPickerSwatch(id: item.id)
        } label: {
            Circle()
                .fill(color)
                .frame(width: 44, height: 44)
                .overlay {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.headline)
                            .foregroundStyle(color.contrastingTextColor)
                    }
                }
                .overlay {
                    Circle()
                        .strokeBorder(.primary.opacity(selected ? 0.4 : 0), lineWidth: 2)
                }
        }
        .buttonStyle(.plain)
        // The palette has no localized color names. Expose its displayed RGB
        // value so every swatch remains distinguishable with VoiceOver.
        .accessibilityLabel(Text(verbatim: String(format: "#%06X", UInt32(truncatingIfNeeded: argb) & 0xFFFFFF)))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// Decodes a packed ARGB long (from Compose's `Color.toArgb()`) into a SwiftUI
    /// `Color`. Mirrors `cipherColor` but takes the 32-bit value already masked to a
    /// non-negative `Int64` by the bridge.
    private func swatchColor(_ argb: Int64) -> Color {
        Color(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255.0,
            green: Double((argb >> 8) & 0xFF) / 255.0,
            blue: Double(argb & 0xFF) / 255.0,
            opacity: Double((argb >> 24) & 0xFF) / 255.0
        )
    }
}
