import SwiftUI
import KeyguardShared

struct SendDetailView: View {
    let detail: SendDetailSnapshot
    let invoke: (String) -> Void
    let copy: () -> Void
    let share: () -> Void
    let edit: () -> Void

    var body: some View {
        Group {
            if detail.notFound {
                ContentUnavailableView {
                    Label(L10n.sendViewNotFoundTitle, systemImage: "questionmark.folder")
                }
            } else if detail.isLoading {
                LoadingIndicator()
            } else {
                content
            }
        }
        #if os(iOS)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var content: some View {
        DetailForm(
            items: detail.items,
            invoke: invoke
        ) {
            DetailIdentityHeader(title: detail.title) { size in
                Image(systemName: typeSymbol(detail.typeIcon))
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.tint)
                    .frame(width: size, height: size)
                    .accessibilityHidden(true)
            }
        }
        .toolbar { detailToolbar }
    }

    private var actionPlacement: ToolbarItemPlacement {
        #if os(macOS)
        .primaryAction
        #else
        .topBarTrailing
        #endif
    }

    @ToolbarContentBuilder
    private var detailToolbar: some ToolbarContent {
        #if os(macOS)
        if #available(macOS 26.0, *) {
            ToolbarSpacer(.fixed, placement: .primaryAction)
        }
        #endif
        if detail.canCopy {
            ToolbarItem(id: "send.item.copy", placement: actionPlacement) {
                Button(L10n.sendActionCopyLinkTitle, systemImage: "doc.on.doc") {
                    copy()
                }
                .help(L10n.sendActionCopyLinkTitle)
            }
        }
        if detail.canShare {
            ToolbarItem(id: "send.item.share", placement: actionPlacement) {
                Button(L10n.share, systemImage: "square.and.arrow.up") {
                    share()
                }
                .help(L10n.share)
            }
        }
        if detail.canEdit {
            ToolbarItem(id: "send.item.edit", placement: actionPlacement) {
                Button(L10n.edit, systemImage: "pencil") {
                    edit()
                }
                .labelStyle(.iconOnly)
                .help(L10n.edit)
            }
        }
        if !detail.actions.isEmpty {
            ToolbarItem(id: "send.item.more", placement: actionPlacement) {
                Menu(L10n.moreActions, systemImage: "ellipsis") {
                    listActionMenuItems(actions: detail.actions) {
                        invoke($0)
                    }
                }
                .help(L10n.moreActions)
            }
        }
    }

    // `typeIcon` is the shared `DSend.Type` name (None / File / Text).
    private func typeSymbol(_ icon: String) -> String {
        switch icon {
        case "Text": return "text.alignleft"
        case "File": return "doc"
        default: return "paperplane"
        }
    }
}
