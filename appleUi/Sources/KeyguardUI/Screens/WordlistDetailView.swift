import SwiftUI
import KeyguardShared

struct WordlistDetailView: View {
    // Retained by navigation bars while a Generator stack is detached; see StackCipherDetailView.
    let navigationModel: NavigationModel
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    let entry: ScreenEntrySnapshot
    private var wordlistId: Int64 { entry.wordlistId?.int64Value ?? 0 }

    @State private var renaming = false
    @State private var pendingDelete = false
    @State private var wordSheet: WordlistWordSheet?
    @State private var pendingWordDelete: String?

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
        .sheet(item: $wordSheet) { sheet in
            WordlistWordView(wordlistId: wordlistId, mode: sheet)
        }
        .alert(
            L10n.wordlistDeleteWordConfirmationTitle,
            isPresented: Binding(
                get: { pendingWordDelete != nil },
                set: { if !$0 { pendingWordDelete = nil } }
            ),
            presenting: pendingWordDelete
        ) { word in
            Button(L10n.delete, role: .destructive) {
                wordlistsModel.deleteWord(wordlistId: wordlistId, word: word)
            }
            Button(L10n.cancel, role: .cancel) {}
        } message: { word in
            Text(word)
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
                // The text is the word itself; the id may carry a suffix that tells
                // repeated words apart.
                .contextMenu { wordContextMenu(word.text) }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    wordSwipeActions(word.text)
                }
            }
        }
        .scrollsToTop(onChangeOf: snapshot.resultQuery, topId: snapshot.words.first?.id)
    }

    @ViewBuilder
    private func wordContextMenu(_ word: String) -> some View {
        Button {
            wordSheet = .edit(word)
        } label: {
            Label(L10n.edit, systemImage: "pencil")
        }
        Divider()
        Button(role: .destructive) {
            pendingWordDelete = word
        } label: {
            Label(L10n.delete, systemImage: "trash")
        }
    }

    /// Danger is a tint, not the destructive role: that role animates the row out
    /// before the confirmation appears.
    @ViewBuilder
    private func wordSwipeActions(_ word: String) -> some View {
        Button {
            pendingWordDelete = word
        } label: {
            Label(L10n.delete, systemImage: "trash")
        }
        .tint(Color(platform: .platformDanger))
        Button {
            wordSheet = .edit(word)
        } label: {
            Label(L10n.edit, systemImage: "pencil")
        }
        .tint(.accentColor)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem {
            Button {
                wordSheet = .add
            } label: {
                Label(L10n.wordlistAddWordsTitle, systemImage: "plus")
            }
            .disabled(snapshot.status != WordlistLoadStatus.ready || snapshot.notFound)
        }
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

enum WordlistWordSheet: Identifiable {
    case add
    case edit(String)

    var id: String {
        switch self {
        case .add: "add"
        case let .edit(word): "edit:\(word)"
        }
    }
}

/// Adds words, one per line, or edits one word of a wordlist.
struct WordlistWordView: View {
    @Environment(WordlistsModel.self) private var wordlistsModel
    @Environment(\.dismiss) private var dismiss

    let wordlistId: Int64
    let mode: WordlistWordSheet

    @State private var text: String = ""
    @State private var isSaving = false
    @State private var errorText: String?

    private var canSave: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSaving
    }

    private var isAdding: Bool {
        if case .add = mode { true } else { false }
    }

    private var title: String {
        isAdding ? L10n.wordlistAddWordsTitle : L10n.wordlistEditWordTitle
    }

    var body: some View {
        ModalSheet(
            title: title,
            width: 460,
            height: 320,
            detents: [.medium],
            dismissLabel: L10n.cancel
        ) {
            Form {
                Section {
                    // Adding takes several words, one per line.
                    TextField(
                        isAdding ? L10n.wordlistWords : L10n.wordlistWord,
                        text: $text,
                        axis: isAdding ? .vertical : .horizontal
                    )
                    .lineLimit(isAdding ? 3...8 : 1...1)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if isAdding {
                            Text(L10n.wordlistAddWordsDescription)
                        }
                        if let errorText {
                            Text(errorText).foregroundStyle(.red)
                        }
                    }
                }
            }
            .formStyle(.grouped)
        } actions: {
            Button(L10n.save) { save() }
                .keyboardShortcut(.defaultAction)
                .disabled(!canSave)
        }
        .onAppear {
            if case let .edit(word) = mode { text = word }
        }
    }

    private func save() {
        isSaving = true
        errorText = nil
        Task {
            do {
                switch mode {
                case .add:
                    try await wordlistsModel.addWords(wordlistId: wordlistId, text: text)
                case let .edit(word):
                    try await wordlistsModel.editWord(wordlistId: wordlistId, from: word, to: text)
                }
                dismiss()
            } catch {
                errorText = error.localizedDescription
                isSaving = false
            }
        }
    }
}
