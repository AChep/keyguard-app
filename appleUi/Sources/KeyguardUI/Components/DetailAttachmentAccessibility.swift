import SwiftUI
import KeyguardShared

/// A progress view inside a button's label can otherwise replace its AX button role.
/// Keep preview activation available while speaking the current download state.
struct DetailAttachmentAccessibility: ViewModifier {
    let item: VaultItemSnapshot
    let invoke: (String) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if let clickActionId = item.clickActionId {
            content.accessibilityRepresentation {
                Button {
                    invoke(clickActionId)
                } label: {
                    Text([item.title, item.text].compactMap { $0 }.joined(separator: ", "))
                }
                .accessibilityValue(statusText)
            }
        } else {
            content
        }
    }

    private var statusText: String {
        guard let status = item.attachment else { return "" }
        if status.status == .loading { return status.downloadedText ?? "" }
        if status.status == .failed {
            return status.autoResume ? L10n.fileStatusDownloadFailedAutoResuming : L10n.fileStatusDownloadingFailed
        }
        if status.status == .downloaded { return L10n.fileStatusDownloaded }
        if status.status == .pendingUpload { return L10n.fileStatusPendingUpload }
        return ""
    }
}
