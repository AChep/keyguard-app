import SwiftUI

/// Render only when the payload changes, independently of live detail updates.
struct DetailQRCode: View {
    let text: String
    @State private var renderedText: String?
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if renderedText == text, let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 200, height: 200)
                    .padding(12)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(Color(platform: .platformSeparator), lineWidth: 1)
                    }
                    .accessibilityElement()
                    .accessibilityLabel(L10n.barcodetypeTitle)
            }
        }
        .task(id: text) { await render() }
    }

    @MainActor
    private func render() async {
        guard renderedText != text else { return }
        let payload = text
        let worker = Task.detached(priority: .userInitiated) {
            guard !Task.isCancelled else { return nil as CGImage? }
            return BarcodeRenderer.image(data: payload, format: "QR_CODE")
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
