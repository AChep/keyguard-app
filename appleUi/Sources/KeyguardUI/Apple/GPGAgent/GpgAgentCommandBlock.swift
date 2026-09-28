#if os(macOS)
import AppKit
import SwiftUI

struct GpgAgentCommandBlock: View {
    let text: String
    @State private var copied = false
    @State private var copyGeneration = 0

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(text)
                .font(.callout.monospaced())
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                NSPasteboard.general.clearContents()
                copied = NSPasteboard.general.setString(text, forType: .string)
                copyGeneration += 1
            } label: {
                Label(copied ? L10n.copiedValue : L10n.copy, systemImage: copied ? "checkmark" : "doc.on.doc")
                    .labelStyle(.iconOnly)
            }
            .buttonStyle(.borderless)
            .help(copied ? L10n.copiedValue : L10n.copy)
            .accessibilityInputLabels([L10n.copy])
            .task(id: copyGeneration) {
                guard copied else { return }
                do {
                    try await Task.sleep(for: .milliseconds(1500))
                    copied = false
                } catch {}
            }
        }
        .padding(10)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
    }
}
#endif
