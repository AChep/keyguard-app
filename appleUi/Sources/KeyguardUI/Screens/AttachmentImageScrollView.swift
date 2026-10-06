#if os(iOS)
import SwiftUI

/// Fits the image after UIKit has assigned the viewport its actual bounds.
/// SwiftUI can call updateUIView before that first layout or a window resize.
final class AttachmentImageScrollView: UIScrollView {
    var imageView: UIImageView?
    private var viewportSize: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, bounds.height > 0 else { return }

        if viewportSize != bounds.size {
            viewportSize = bounds.size
            setZoomScale(minimumZoomScale, animated: false)
        }
        guard zoomScale == minimumZoomScale else { return }

        let imageFrame = CGRect(origin: .zero, size: bounds.size)
        if imageView?.frame != imageFrame {
            imageView?.frame = imageFrame
        }
        if contentSize != bounds.size {
            contentSize = bounds.size
        }
    }
}
#endif
