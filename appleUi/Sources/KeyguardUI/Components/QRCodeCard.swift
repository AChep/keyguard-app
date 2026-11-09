import SwiftUI

/// A QR code on a white card, so that cameras read it in both themes. Renders only
/// when the payload changes, independently of live updates of the screen.
struct QRCodeCard: View {
    let text: String
    /// The side of the code, without the card around it.
    var size: CGFloat = 200
    @Environment(\.displayScale) private var displayScale
    @State private var renderedText: String?
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if renderedText == text, let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .padding(12)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(Color(platform: .platformSeparator), lineWidth: 1)
                    }
                    .accessibilityElement()
                    .accessibilityLabel(L10n.barcodetypeTitle)
            } else {
                ProgressView()
            }
        }
        .frame(width: size + 24, height: size + 24)
        .task(id: text) { await render() }
    }

    @MainActor
    private func render() async {
        guard renderedText != text else { return }
        let payload = text
        let scale = displayScale
        let worker = Task.detached(priority: .userInitiated) {
            guard !Task.isCancelled else { return nil as CGImage? }
            return BarcodeRenderer.image(data: payload, format: "QR_CODE", displayScale: scale)
        }
        let result = await withTaskCancellationHandler {
            await worker.value
        } onCancel: {
            worker.cancel()
        }
        guard !Task.isCancelled else { return }
        renderedText = payload
        image = result
    }
}
