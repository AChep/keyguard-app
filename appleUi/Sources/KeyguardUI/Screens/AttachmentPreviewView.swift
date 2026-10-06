import SwiftUI
import KeyguardShared
#if canImport(AppKit)
import AppKit
#endif
#if canImport(UIKit)
import UIKit
#endif

struct AttachmentPreviewView: View {
    @Environment(DialogsModel.self) private var dialogsModel

    /// `0` renders the markdown, `1` shows the raw source.
    @State private var markdownMode = 0

    var body: some View {
        ModalSheet(
            title: dialogsModel.attachmentPreview?.fileName ?? L10n.attachment,
            width: 640,
            height: 480,
            detents: [.large]
        ) {
            if let snapshot = dialogsModel.attachmentPreview {
                content(snapshot)
            } else {
                // Briefly nil mid-dismissal; render nothing.
                Color.clear
            }
        } actions: {
            if dialogsModel.attachmentPreview?.canCopy == true {
                Button {
                    dialogsModel.copyAttachmentPreviewText()
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .help(L10n.copy)
                .accessibilityLabel(L10n.copy)
            }
        }
    }

    @ViewBuilder
    private func content(_ snapshot: AttachmentPreviewSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if snapshot.kind == AttachmentPreviewKindSnapshot.markdown {
                Picker("", selection: $markdownMode) {
                    Text(L10n.fileActionPreviewTitle).tag(0)
                    Text(L10n.attachmentPreviewActionSource).tag(1)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                // The empty title + `.labelsHidden()` keeps the segmented layout but
                // leaves VoiceOver with no name for the control; describe that it
                // toggles rendered markdown vs. the raw source.
                .accessibilityLabel("\(L10n.fileActionPreviewTitle) / \(L10n.attachmentPreviewActionSource)")
            }

            Group {
                if snapshot.kind == AttachmentPreviewKindSnapshot.loading {
                    loadingPane()
                } else if snapshot.kind == AttachmentPreviewKindSnapshot.image {
                    imagePane(snapshot)
                } else if snapshot.kind == AttachmentPreviewKindSnapshot.text {
                    textPane(snapshot)
                } else if snapshot.kind == AttachmentPreviewKindSnapshot.markdown {
                    markdownPane(snapshot)
                } else {
                    errorPane(snapshot.errorMessage)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(24)
    }

    @ViewBuilder
    private func loadingPane() -> some View {
        AttachmentPreviewLoadingPane()
    }

    @ViewBuilder
    private func imagePane(_ snapshot: AttachmentPreviewSnapshot) -> some View {
        AttachmentPreviewImageView(
            // Data-to-NSData bridging can create a new object on every render;
            // the decode key compares its immutable bytes as well as identity.
            data: snapshot.imageData.map { $0 as NSData },
            decodeErrorMessage: snapshot.imageDecodeErrorMessage,
            accessibilityLabel: snapshot.fileName
        )
    }

    @ViewBuilder
    private func textPane(_ snapshot: AttachmentPreviewSnapshot) -> some View {
        ScrollView([.vertical, .horizontal]) {
            HighlightedTextView(
                text: snapshot.text ?? "",
                spans: snapshot.spans
            )
            .padding(12)
        }
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func markdownPane(_ snapshot: AttachmentPreviewSnapshot) -> some View {
        if markdownMode == 0 {
            ScrollView {
                MarkdownTextView(text: snapshot.text ?? "")
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
        } else {
            textPane(snapshot)
        }
    }

    @ViewBuilder
    private func errorPane(_ message: String?) -> some View {
        AttachmentPreviewErrorPane(message: message)
    }
}

private struct AttachmentPreviewLoadingPane: View {
    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text(L10n.loading)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct AttachmentPreviewImageView: View {
    let data: NSData?
    let decodeErrorMessage: String?
    let accessibilityLabel: String

    @State private var decodedImage: DecodedImage?

    private var key: AttachmentImageDecodeKey {
        AttachmentImageDecodeKey(data: data, decodeErrorMessage: decodeErrorMessage)
    }

    var body: some View {
        Group {
            if let decodedImage, decodedImage.key == key {
                if let image = decodedImage.image {
                    #if os(iOS)
                    // Pinch / double-tap / pan, the Photos & QuickLook gesture set.
                    ZoomableImageView(image: image, accessibilityLabel: accessibilityLabel)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    #else
                    // Pinch / scroll to magnify, drag to pan while zoomed, double-click to reset.
                    MacZoomableImageView(image: image, accessibilityLabel: accessibilityLabel)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    #endif
                } else {
                    AttachmentPreviewErrorPane(message: decodeErrorMessage)
                }
            } else {
                AttachmentPreviewLoadingPane()
            }
        }
        .task(id: key) {
            await decodeImage(key: key, data: data)
        }
    }

    private func decodeImage(key: AttachmentImageDecodeKey, data: NSData?) async {
        do {
            try Task.checkCancellation()
            let image = data.flatMap { PlatformImage(data: $0 as Data) }
            try Task.checkCancellation()
            decodedImage = DecodedImage(key: key, image: image)
        } catch is CancellationError {
            // SwiftUI cancels this task when the view disappears or `key` changes.
        } catch {
            decodedImage = DecodedImage(key: key, image: nil)
        }
    }
}

private struct DecodedImage {
    let key: AttachmentImageDecodeKey
    let image: PlatformImage?
}

private struct AttachmentPreviewErrorPane: View {
    let message: String?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(message ?? L10n.errorFailedUnknown)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct HighlightedTextView: View {
    let text: String
    let spans: [AttachmentPreviewSpanSnapshot]

    @State private var highlightedText: HighlightedText?
    @ScaledMetric(relativeTo: .body) private var fontSize = PlatformFont.systemFontSize

    private var key: HighlightedTextKey {
        HighlightedTextKey(text: text, spans: spans, fontSize: fontSize)
    }

    var body: some View {
        Group {
            if let highlightedText, highlightedText.key == key {
                Text(highlightedText.value)
            } else {
                Text(text)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.primary)
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: key) {
            await highlightText(key: key, text: text, spans: spans)
        }
    }

    private func highlightText(
        key: HighlightedTextKey,
        text: String,
        spans: [AttachmentPreviewSpanSnapshot]
    ) async {
        do {
            let attributedText = try makeAttributedText(text: text, spans: spans)
            try Task.checkCancellation()
            highlightedText = HighlightedText(key: key, value: attributedText)
        } catch is CancellationError {
            // SwiftUI cancels this task when the view disappears or `key` changes.
        } catch {
            highlightedText = HighlightedText(key: key, value: AttributedString(text))
        }
    }

    private func makeAttributedText(
        text: String,
        spans: [AttachmentPreviewSpanSnapshot]
    ) throws -> AttributedString {
        try Task.checkCancellation()
        let result = NSMutableAttributedString(string: text)
        let fullRange = NSRange(location: 0, length: result.length)
        result.addAttribute(
            .font,
            value: PlatformFont.monospacedSystemFont(ofSize: fontSize, weight: .regular),
            range: fullRange
        )
        result.addAttribute(.foregroundColor, value: PlatformColor.platformLabel, range: fullRange)

        for (index, span) in spans.enumerated() {
            if index.isMultiple(of: 64) {
                try Task.checkCancellation()
            }
            let location = Int(span.start)
            let length = Int(span.end) - location
            guard location >= 0, length > 0, location + length <= result.length else { continue }
            let range = NSRange(location: location, length: length)
            if span.colorArgb != 0 {
                result.addAttribute(.foregroundColor, value: platformColor(argb: span.colorArgb), range: range)
            }
            if span.bold {
                result.addAttribute(
                    .font,
                    value: PlatformFont.monospacedSystemFont(ofSize: fontSize, weight: .bold),
                    range: range
                )
            }
        }
        try Task.checkCancellation()
        return AttributedString(result)
    }

    private func platformColor(argb: Int64) -> PlatformColor {
        PlatformColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}

private struct HighlightedText {
    let key: HighlightedTextKey
    let value: AttributedString
}

private struct HighlightedTextKey: Equatable {
    let text: String
    let spans: [HighlightedTextSpanKey]
    let fontSize: CGFloat

    init(text: String, spans: [AttachmentPreviewSpanSnapshot], fontSize: CGFloat) {
        self.text = text
        self.spans = spans.map { HighlightedTextSpanKey(span: $0) }
        self.fontSize = fontSize
    }
}

private struct HighlightedTextSpanKey: Equatable {
    let start: Int32
    let end: Int32
    let colorArgb: Int64
    let bold: Bool

    init(span: AttachmentPreviewSpanSnapshot) {
        self.start = span.start
        self.end = span.end
        self.colorArgb = span.colorArgb
        self.bold = span.bold
    }
}

#if os(iOS)
private struct ZoomableImageView: UIViewRepresentable {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let image: UIImage
    let accessibilityLabel: String

    func makeUIView(context: Context) -> AttachmentImageScrollView {
        let scrollView = AttachmentImageScrollView()
        scrollView.delegate = context.coordinator
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 6
        scrollView.bouncesZoom = true
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.backgroundColor = .clear

        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        imageView.isAccessibilityElement = true
        imageView.accessibilityLabel = accessibilityLabel
        scrollView.addSubview(imageView)
        scrollView.imageView = imageView
        context.coordinator.imageView = imageView
        context.coordinator.reduceMotion = reduceMotion

        let doubleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleDoubleTap(_:))
        )
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        return scrollView
    }

    func updateUIView(_ scrollView: AttachmentImageScrollView, context: Context) {
        context.coordinator.reduceMotion = reduceMotion
        let imageView = context.coordinator.imageView
        imageView?.accessibilityLabel = accessibilityLabel
        if imageView?.image !== image {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: false)
            imageView?.image = image
        }
        scrollView.setNeedsLayout()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        var reduceMotion = false

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        // Keep the image centred when it's smaller than the viewport.
        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            guard let imageView else { return }
            let offsetX = max((scrollView.bounds.width - scrollView.contentSize.width) * 0.5, 0)
            let offsetY = max((scrollView.bounds.height - scrollView.contentSize.height) * 0.5, 0)
            imageView.center = CGPoint(
                x: scrollView.contentSize.width * 0.5 + offsetX,
                y: scrollView.contentSize.height * 0.5 + offsetY
            )
        }

        @objc func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
            guard let scrollView = gesture.view as? UIScrollView else { return }
            if scrollView.zoomScale > scrollView.minimumZoomScale {
                scrollView.setZoomScale(scrollView.minimumZoomScale, animated: !reduceMotion)
            } else {
                let point = gesture.location(in: imageView)
                let size = CGSize(
                    width: scrollView.bounds.width / 3,
                    height: scrollView.bounds.height / 3
                )
                let rect = CGRect(
                    origin: CGPoint(x: point.x - size.width / 2, y: point.y - size.height / 2),
                    size: size
                )
                scrollView.zoom(to: rect, animated: !reduceMotion)
            }
        }
    }
}
#endif

#if os(macOS)
private struct MacZoomableImageView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let image: NSImage
    let accessibilityLabel: String

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private let minScale: CGFloat = 1
    private let maxScale: CGFloat = 6

    var body: some View {
        let magnify = MagnificationGesture()
            .onChanged { value in
                scale = clampedScale(lastScale * value)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= minScale { resetPan() }
            }

        let pan = DragGesture()
            .onChanged { value in
                guard scale > minScale else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                lastOffset = offset
            }

        return Image(platform: image)
            .resizable()
            .scaledToFit()
            .accessibilityLabel(accessibilityLabel)
            .scaleEffect(scale)
            .offset(offset)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            // Pan is guarded internally (only moves while zoomed in), so it can be
            // attached unconditionally alongside the magnify gesture.
            .simultaneousGesture(pan)
            .gesture(magnify)
            .onTapGesture(count: 2) {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                    if scale > minScale {
                        scale = minScale
                        lastScale = minScale
                        resetPan()
                    } else {
                        scale = 2
                        lastScale = 2
                    }
                }
            }
            .clipped()
    }

    private func clampedScale(_ value: CGFloat) -> CGFloat {
        min(max(value, minScale), maxScale)
    }

    private func resetPan() {
        offset = .zero
        lastOffset = .zero
    }
}
#endif
