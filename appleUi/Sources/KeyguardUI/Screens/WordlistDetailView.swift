import SwiftUI
import KeyguardShared

struct WordlistDetailView: View {
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    let entry: ScreenEntrySnapshot
    private var wordlistId: Int64 { entry.wordlistId?.int64Value ?? 0 }

    @State private var renaming = false
    @State private var pendingDelete = false

    private var snapshot: WordlistDetailSnapshot {
        navigationModel.navStacks.values.joined().first { $0.instanceId == entry.instanceId }?.wordlistDetail
            ?? WordlistDetailSnapshot.companion.empty
    }

    private var displayTitle: String {
        snapshot.title.isEmpty ? entry.title : snapshot.title
    }

    @State private var query = ""

    private var searchQuery: Binding<String> {
        Binding(
            get: { query },
            set: { text in
                query = text
                navigationModel.setEntryListQuery(instanceId: entry.instanceId, text: text)
            }
        )
    }

    var body: some View {
        Group {
            if snapshot.status == WordlistLoadStatus.failed {
                ContentUnavailableView {
                    Label(L10n.errorFailedUnknown, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button(L10n.retry) { navigationModel.retryEntryList(instanceId: entry.instanceId) }
                }
            } else if snapshot.notFound {
                ContentUnavailableView(L10n.itemNotFound, systemImage: "text.book.closed")
            } else {
                SnapshotContent(
                    loaded: snapshot.status == WordlistLoadStatus.ready,
                    isEmpty: snapshot.words.isEmpty && !snapshot.searching
                ) {
                    empty
                } content: {
                    list
                }
            }
        }
        .overlay(alignment: .top) {
            if snapshot.searching { ProgressView().controlSize(.small).padding(8) }
        }
        .navigationTitle(displayTitle)
        .listSearchable(text: searchQuery, prompt: Text(L10n.wordlistWordSearchPlaceholder), macOSPlacement: .toolbar)
        .onChange(of: snapshot.queryRevision) { _, _ in query = snapshot.query }
        .onAppear { query = snapshot.query }
        .toolbar { toolbar }
        .sheet(isPresented: $renaming) {
            WordlistRenameView(wordlistId: wordlistId, currentName: displayTitle)

        }
        .alert(L10n.wordlistDeleteOneConfirmationTitle, isPresented: $pendingDelete) {
            Button(L10n.delete, role: .destructive) {
                wordlistsModel.deleteWordlists(ids: [wordlistId], onSuccess: { dismiss() })
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: {
            Text(L10n.wordlistDeletePermanentWarning(displayTitle))
        }
    }

    @ViewBuilder
    private var empty: some View {
        if snapshot.resultQuery.isEmpty {
            ContentUnavailableView {
                Label(L10n.wordlistWordsEmptyLabel, systemImage: "text.book.closed")
            }
        } else {
            // The screen is `.searchable`; use the system no-results state so the
            // query is echoed back.
            ContentUnavailableView.search(text: snapshot.resultQuery)
        }
    }

    private var list: some View {
        List {
            ForEach(snapshot.words, id: \.id) { word in
                Text(
                    highlightedText(
                        word.text,
                        utf16Ranges: word.highlights.compactMap { span in
                            guard span.start >= 0, span.endExclusive >= span.start else { return nil }
                            return Int(span.start)..<Int(span.endExclusive)
                        })
                )
                .font(.body.monospaced())
                .textSelection(.enabled)
            }
        }
        .scrollsToTop(onChangeOf: snapshot.resultQuery, topId: snapshot.words.first?.id)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem {
            Menu {
                Button(L10n.rename) { renaming = true }
                Divider()
                Button(L10n.delete, role: .destructive) { pendingDelete = true }
            } label: {
                Label(L10n.moreActions, systemImage: "ellipsis.circle")
            }
            .disabled(snapshot.status != WordlistLoadStatus.ready || snapshot.notFound)
        }
    }
}
