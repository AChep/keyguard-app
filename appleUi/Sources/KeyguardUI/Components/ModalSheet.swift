import SwiftUI

struct ModalSheet<Content: View, Actions: View, HeaderActions: View>: View {
    let title: String
    /// macOS panel width (ignored on iOS).
    var width: CGFloat = 520
    /// macOS panel height (ignored on iOS).
    var height: CGFloat = 560
    /// iOS presentation detents (ignored on macOS).
    var detents: Set<PresentationDetent> = [.large]
    /// The leading dismiss button's label — "Close" for informational sheets,
    /// "Cancel" for editors that can be abandoned.
    var dismissLabel: String = L10n.close
    /// An embedded editor step can return to its parent without dismissing the sheet.
    var onDismiss: (() -> Void)? = nil
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions
    /// Trailing title-row actions on macOS; precede confirmation actions on iOS.
    @ViewBuilder let headerActions: HeaderActions

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        #if os(macOS)
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .font(.headline)
                Spacer()
                headerActions
            }
            .padding(16)
            Divider()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            HStack {
                Spacer()
                Button(dismissLabel) {
                    if let onDismiss { onDismiss() } else { dismiss() }
                }
                .keyboardShortcut(.cancelAction)
                actions
            }
            .padding(16)
        }
        .frame(
            minWidth: width, idealWidth: width,
            minHeight: min(height, 360), idealHeight: height,
            maxHeight: .infinity
        )
        #else
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(dismissLabel) {
                            if let onDismiss { onDismiss() } else { dismiss() }
                        }
                    }
                    ToolbarItemGroup(placement: .confirmationAction) {
                        headerActions
                        actions
                    }
                }
        }
        .presentationDetents(detents)
        .presentationDragIndicator(.visible)
        #endif
    }
}

extension ModalSheet where HeaderActions == EmptyView {
    init(
        title: String,
        width: CGFloat = 520,
        height: CGFloat = 560,
        detents: Set<PresentationDetent> = [.large],
        dismissLabel: String = L10n.close,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: () -> Content,
        @ViewBuilder actions: () -> Actions
    ) {
        self.init(
            title: title,
            width: width,
            height: height,
            detents: detents,
            dismissLabel: dismissLabel,
            onDismiss: onDismiss,
            content: content,
            actions: actions,
            headerActions: { EmptyView() }
        )
    }
}

extension ModalSheet where Actions == EmptyView, HeaderActions == EmptyView {
    init(
        title: String,
        width: CGFloat = 520,
        height: CGFloat = 560,
        detents: Set<PresentationDetent> = [.large],
        dismissLabel: String = L10n.close,
        @ViewBuilder content: () -> Content
    ) {
        self.init(
            title: title,
            width: width,
            height: height,
            detents: detents,
            dismissLabel: dismissLabel,
            content: content,
            actions: { EmptyView() }
        )
    }
}
