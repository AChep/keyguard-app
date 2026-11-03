import SwiftUI
import KeyguardShared

struct AttachmentArchiveView: View {
    let snapshot: AttachmentPreviewSnapshot
    let savedScrollTarget: String?
    let onScroll: (String?) -> Void
    @State private var scrollTarget: String?
    @Environment(DialogsModel.self) private var dialogsModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !snapshot.archiveBreadcrumbs.isEmpty {
                breadcrumbs
            }
            if snapshot.archiveEntries.isEmpty {
                ContentUnavailableView(L10n.attachmentArchiveEmpty, systemImage: "folder")
            } else {
                entries
            }
        }
        .disabled(snapshot.busy || snapshot.exportId != nil)
    }

    private var breadcrumbs: some View {
        let names = snapshot.archiveBreadcrumbs
        return BreadcrumbBar(
            nodes: names.enumerated().map { index, name in
                BreadcrumbBar.Node(
                    id: String(index),
                    title: name,
                    action: index == names.count - 1
                        ? nil : { dialogsModel.openAttachmentArchiveBreadcrumb(index) }
                )
            }
        )
    }

    private var entries: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(snapshot.archiveEntries.enumerated()), id: \.element.id) { index, entry in
                    VStack(spacing: 0) {
                        if index > 0 {
                            Divider()
                        }
                        row(entry)
                            .padding(.vertical, 4)
                    }
                }
            }
            .scrollTargetLayout()
        }
        .scrollPosition(id: $scrollTarget)
        .onAppear { scrollTarget = savedScrollTarget }
        .onChange(of: scrollTarget) { _, target in onScroll(target) }
    }

    private func row(_ entry: AttachmentArchiveEntrySnapshot) -> some View {
        DetailAccessoryRow {
            Button {
                dialogsModel.openAttachmentArchiveEntry(entry.id, save: false)
            } label: {
                DetailRowLabel(
                    systemImage: entry.directory ? "folder" : "doc",
                    title: entry.name,
                    subtitle: subtitle(entry),
                    // Folders open, files preview: the same cues as the attachment rows.
                    disclosure: entry.directory ? "chevron.forward" : entry.canOpen ? "eye" : nil
                )
                .touchTarget()
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!entry.canOpen)
        } accessories: {
            if entry.canSave {
                DetailIconButton(title: L10n.attachmentArchiveSave, systemImage: "square.and.arrow.down") {
                    dialogsModel.openAttachmentArchiveEntry(entry.id, save: true)
                }
                .accessibilityLabel("\(L10n.attachmentArchiveSave): \(entry.name)")
            }
        }
    }

    private func subtitle(_ entry: AttachmentArchiveEntrySnapshot) -> String {
        var parts: [String] = []
        if let size = entry.size { parts.append(size) }
        if entry.encrypted { parts.append(L10n.attachmentArchiveEncrypted) }
        if !entry.directory, !entry.canOpen, !entry.canSave { parts.append(L10n.attachmentArchiveUnavailable) }
        return parts.joined(separator: " · ")
    }
}
