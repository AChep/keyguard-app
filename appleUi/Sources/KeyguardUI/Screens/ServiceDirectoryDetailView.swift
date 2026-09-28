import SwiftUI
import KeyguardShared

struct ServiceDirectoryDetailView: View {
    @Environment(ServiceDirectoryModel.self) private var directoriesModel

    var kind: String = ""
    var itemId: String = ""
    /// When non-nil this view renders a navigation-stack entry's inline snapshot
    /// instead of the single-slot observation, and skips owning the observation.
    var entry: ScreenEntrySnapshot? = nil

    private var detail: ServiceDirectoryDetailSnapshot {
        entry?.serviceDirectoryDetail ?? directoriesModel.directoryDetail
    }

    var body: some View {
        Group {
            if detail.failed {
                ContentUnavailableView(L10n.errorFailedUnknown, systemImage: "exclamationmark.triangle")
            } else if detail.notFound {
                ContentUnavailableView(L10n.errorNotFound, systemImage: "magnifyingglass")
            } else if !detail.loaded {
                LoadingIndicator()
            } else {
                content
            }
        }
        .navigationTitle(detail.title)
        .observing(
            enabled: entry == nil,
            start: { directoriesModel.startServiceDirectoryDetailObservation(kind: kind, itemId: itemId) },
            stop: { directoriesModel.stopServiceDirectoryDetailObservation() }
        )
    }

    private var content: some View {
        ScrollView {
            ServiceDirectoryDetailContent(detail: detail)
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct ServiceDirectoryDetailContent: View {
    let detail: ServiceDirectoryDetailSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if !detail.chips.isEmpty {
                chips
            }
            if let notes = detail.notes, !notes.isEmpty {
                notesView(notes)
            }
            if !detail.links.isEmpty {
                links
            }
        }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(detail.chips, id: \.self) { chip in
                    Text(chip)
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(platform: .platformControlBackground), in: Capsule())
                }
            }
        }
    }

    private func notesView(_ notes: String) -> some View {
        MarkdownTextView(text: notes)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var links: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(detail.links, id: \.url) { link in
                if let url = URL(string: link.url) {
                    Link(destination: url) {
                        HStack(spacing: 8) {
                            Image(systemName: icon(for: link.url))
                                .foregroundStyle(.tint)
                            Text(link.title)
                                .foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "arrow.up.right")
                                .foregroundStyle(.secondary)
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(platform: .platformControlBackground), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func icon(for url: String) -> String {
        url.hasPrefix("mailto:") ? "envelope" : "safari"
    }
}
