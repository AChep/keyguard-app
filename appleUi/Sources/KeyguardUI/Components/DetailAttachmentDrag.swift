import KeyguardShared
import SwiftUI

/// Each gesture owns its platform offer; prepared bytes never enter Downloads.
struct DetailAttachmentDrag: ViewModifier {
    #if os(macOS)
    @Environment(\.layoutDirection) private var layoutDirection
    #endif
    let drag: AttachmentDragSnapshot?

    func body(content: Content) -> some View {
        if let drag {
            #if os(macOS)
            content
                .overlay {
                    DetailAttachmentMacDragHost(preparer: drag, rightToLeft: layoutDirection == .rightToLeft)
                }
                .accessibilityHint(L10n.attachmentDragHint)
            #else
            content
                .onDrag {
                    AttachmentDragProvider.make(preparer: drag)
                }
                .accessibilityHint(L10n.attachmentDragHint)
            #endif
        } else {
            content
        }
    }
}
