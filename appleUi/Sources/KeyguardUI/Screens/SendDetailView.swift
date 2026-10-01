import SwiftUI
import KeyguardShared

struct SendDetailView: View {
    @Environment(SendModel.self) private var sendModel

    private var detail: SendDetailSnapshot { sendModel.sendDetail }

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
            invoke: { sendModel.invokeSendAction(id: $0) }
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
        // Local reveal state belongs to the entity delivered with this snapshot.
        .id(sendModel.sendDetailIdentity)
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
                    sendModel.sendCopy()
                }
                .help(L10n.sendActionCopyLinkTitle)
            }
        }
        if detail.canShare {
            ToolbarItem(id: "send.item.share", placement: actionPlacement) {
                Button(L10n.share, systemImage: "square.and.arrow.up") {
                    sendModel.sendShare()
                }
                .help(L10n.share)
            }
        }
        if detail.canEdit {
            ToolbarItem(id: "send.item.edit", placement: actionPlacement) {
                Button(L10n.edit, systemImage: "pencil") {
                    sendModel.sendEdit()
                }
                .labelStyle(.iconOnly)
                .help(L10n.edit)
            }
        }
        if !detail.actions.isEmpty {
            ToolbarItem(id: "send.item.more", placement: actionPlacement) {
                Menu(L10n.moreActions, systemImage: "ellipsis") {
                    listActionMenuItems(actions: detail.actions) {
                        sendModel.invokeSendAction(id: $0)
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

struct StackSendDetailView: View {
    @Environment(SendModel.self) private var sendModel
    @State private var observationOwner: UUID?

    let sendId: String
    let accountId: String

    var body: some View {
        SendDetailView()
            #if os(macOS)
        .navigationTitle(
            sendModel.sendDetail.title.isEmpty ? L10n.credentialExchangeImportUntitled : sendModel.sendDetail.title)
            #endif
            .observing(
                start: {
                    observationOwner = sendModel.startSendDetailObservation(itemId: sendId, accountId: accountId)
                },
                stop: {
                    sendModel.stopSendDetailObservation(owner: observationOwner)
                    observationOwner = nil
                }
            )
    }
}
