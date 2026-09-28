import SwiftUI
import CoreImage
import KeyguardShared

struct BarcodeView: View {
    @Environment(DialogsModel.self) private var dialogsModel
    @Environment(\.displayScale) private var displayScale
    @State private var barcodeImage = BarcodeImageResult.empty

    var body: some View {
        ModalSheet(
            title: dialogsModel.barcode?.title ?? L10n.barcodetypeTitle,
            width: 420,
            height: 360,
            detents: [.medium, .large]
        ) {
            if let snapshot = dialogsModel.barcode {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        }
    }

    @ViewBuilder
    private func content(_ snapshot: BarcodeSnapshot) -> some View {
        let imageID = BarcodeImageID(snapshot: snapshot, displayScale: displayScale)

        VStack(alignment: .leading, spacing: 16) {
            if snapshot.formatSelectable, snapshot.options.count > 1 {
                formatPicker(snapshot)
            }

            barcode(snapshot, imageID: imageID)
                .frame(maxWidth: .infinity)

            if let note = snapshot.note, !note.isEmpty {
                Text(note)
                    .font(.callout)
                    .textSelection(.enabled)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .task(id: imageID) {
            await renderBarcodeImage(imageID)
        }
    }

    @ViewBuilder
    private func formatPicker(_ snapshot: BarcodeSnapshot) -> some View {
        // A native pop-up button on macOS / menu picker on iOS, so the control
        // reads as an interactive selector (the bare borderless Menu rendered as
        // plain text with no chevron/affordance on macOS).
        let selection = Binding<String>(
            get: { snapshot.options.first(where: { $0.selected })?.id ?? "" },
            set: { dialogsModel.selectBarcodeFormat(id: $0) }
        )
        Picker(L10n.barcodetypeTitle, selection: selection) {
            ForEach(snapshot.options, id: \.id) { option in
                Text(option.title).tag(option.id)
            }
        }
        .pickerStyle(.menu)
        .labelsHidden()
        .fixedSize()
        #if os(macOS)
        .help(snapshot.formatTitle)
        #endif
    }

    @ViewBuilder
    private func barcode(
        _ snapshot: BarcodeSnapshot,
        imageID: BarcodeImageID
    ) -> some View {
        switch barcodeImage.value(for: imageID) {
        case .some(.success(let image)):
            // The barcode is the dialog's primary content, so expose it to
            // VoiceOver with a descriptive label instead of marking it decorative.
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 320, maxHeight: 320)
                .padding(8)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityElement()
                .accessibilityLabel("\(L10n.barcodetypeTitle), \(snapshot.formatTitle)")
        case .some(.failure):
            // Unsupported formats, invalid values, and values too dense for the
            // available display area share a localized explanation.
            ContentUnavailableView {
                Label(
                    L10n.barcodeInvalidOrUnsupportedText,
                    systemImage: "barcode"
                )
            }
            .frame(maxWidth: 320)
            .padding(.vertical, 24)
        case nil:
            Color.clear
                .frame(maxWidth: 320, maxHeight: 320)
        }
    }

    @MainActor
    private func renderBarcodeImage(_ imageID: BarcodeImageID) async {
        guard !barcodeImage.isResolved(for: imageID) else { return }

        barcodeImage = .loading(imageID)
        let image = await BarcodeRenderer.image(for: imageID)
        guard !Task.isCancelled else { return }

        barcodeImage = .resolved(imageID, image)
    }
}

private struct BarcodeImageID: Hashable {
    var data: String
    var format: String
    var displayScale: CGFloat

    init(snapshot: BarcodeSnapshot, displayScale: CGFloat) {
        data = snapshot.data
        format = snapshot.format
        self.displayScale = displayScale
    }
}

private enum BarcodeImageValue {
    case success(CGImage)
    case failure
}

private enum BarcodeImageResult {
    case empty
    case loading(BarcodeImageID)
    case resolved(BarcodeImageID, BarcodeImageValue)

    func value(for imageID: BarcodeImageID) -> BarcodeImageValue? {
        guard case let .resolved(resolvedID, value) = self, resolvedID == imageID else {
            return nil
        }
        return value
    }

    func isResolved(for imageID: BarcodeImageID) -> Bool {
        if case let .resolved(resolvedID, _) = self, resolvedID == imageID {
            return true
        }
        return false
    }

    static func resolved(
        _ imageID: BarcodeImageID,
        _ image: CGImage?
    ) -> BarcodeImageResult {
        .resolved(imageID, image.map(BarcodeImageValue.success) ?? .failure)
    }
}

enum BarcodeRenderer {
    /// Capacity is measured in display pixels, not points: a Retina display can
    /// faithfully show PDF417 symbols that exceed 320 source modules.
    private static let displayWidth: CGFloat = 320
    private static let context = CIContext()

    static func image(data: String, format: String, displayScale: CGFloat = 1) -> CGImage? {
        guard !data.isEmpty, let output = ciImage(data: data, format: format) else {
            return nil
        }

        let targetWidth = displayWidth * max(1, displayScale)
        let extent = output.extent
        guard extent.width > 0, extent.height > 0,
            extent.width <= targetWidth,
            extent.height <= targetWidth
        else { return nil }

        // Rasterize whole modules. Fractional source scaling can drop narrow bars
        // before SwiftUI even draws the bitmap.
        let scaleX = max(1, floor(targetWidth / max(extent.width, extent.height)))
        // 1D barcodes are a single-module-tall strip; give them a fixed bar height
        // instead of preserving their (near-zero) aspect ratio.
        let scaleY: CGFloat =
            format == "CODE_128"
            ? (targetWidth / 3) / extent.height
            : scaleX
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return cgImage
    }

    private static func ciImage(data: String, format: String) -> CIImage? {
        switch format {
        case "QR_CODE":
            return generate(
                "CIQRCodeGenerator",
                message: data.data(using: .utf8),
                extra: ["inputCorrectionLevel": "M"]
            )
        case "PDF_417":
            return generate("CIPDF417BarcodeGenerator", message: data.data(using: .utf8))
        case "AZTEC":
            return generate("CIAztecCodeGenerator", message: data.data(using: .utf8))
        case "CODE_128":
            // Code 128 only encodes Latin-1 / ASCII; reject anything outside it.
            return generate(
                "CICode128BarcodeGenerator",
                message: data.data(using: .isoLatin1),
                extra: ["inputQuietSpace": 8]
            )
        default:
            return nil
        }
    }

    private static func generate(
        _ name: String,
        message: Data?,
        extra: [String: Any] = [:]
    ) -> CIImage? {
        guard let message, let filter = CIFilter(name: name) else { return nil }
        filter.setValue(message, forKey: "inputMessage")
        for (key, value) in extra {
            filter.setValue(value, forKey: key)
        }
        return filter.outputImage
    }
}

private extension BarcodeRenderer {
    static func image(for imageID: BarcodeImageID) async -> CGImage? {
        let task = Task.detached(priority: .userInitiated) {
            guard !Task.isCancelled else { return nil as CGImage? }
            let image = image(data: imageID.data, format: imageID.format, displayScale: imageID.displayScale)
            return Task.isCancelled ? nil : image
        }

        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
    }
}
