import Foundation
import SwiftUI
import AuthenticationServices
import KeyguardShared

#if os(macOS)
import AppKit
#endif

/// The AutoFill manual picker, shared by the macOS and iOS extensions.
struct AutofillCredentialListView: View {
    @State private var model: AutofillListModel
    @State private var query: String = ""

    init(model: AutofillListModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.isUnlocked {
                    listContent
                } else {
                    AutofillUnlockView(model: model.unlockModel)
                }
            }
            .navigationTitle(model.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { model.cancel() }
                        #if os(macOS)
                    .keyboardShortcut(.cancelAction)
                        #endif
                }
            }
        }
        #if os(macOS)
        // The macOS AutoFill panel has no intrinsic size of its own.
        .frame(minWidth: 360, minHeight: 360)
        #endif
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    private var listContent: some View {
        // Keep one search host while results switch between list and empty state;
        // replacing it dismisses the keyboard and loses the displayed query.
        Group {
            listResults
        }
        .searchable(text: $query)
        .disabled(model.isPicking)
    }

    @ViewBuilder
    private var listResults: some View {
        let results = filtered
        if model.isLoading {
            VStack { ProgressView() }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = model.loadError {
            ContentUnavailableView {
                Label(L("error_failed_unknown"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button(L("retry"), action: model.retry)
            }
        } else if results.isEmpty {
            Group {
                if query.isEmpty {
                    ContentUnavailableView {
                        Label(L("autofill_no_matching_logins"), systemImage: "key.fill")
                    } description: {
                        Text(L("autofill_empty"))
                    }
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach([true, false], id: \.self) { suggested in
                    let sectionItems = results.filter { $0.suggested == suggested }
                    if !sectionItems.isEmpty {
                        Section(L(suggested ? "autofill_suggested" : "autofill_other_logins")) {
                            ForEach(sectionItems, id: \.recordId) { item in
                                Button {
                                    model.pick(recordId: item.recordId)
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name.isEmpty ? item.user : item.name)
                                            .foregroundStyle(.primary)
                                        if !item.accountName.isEmpty {
                                            Text(item.accountName).font(.caption2).foregroundStyle(.secondary)
                                        }
                                        if !item.user.isEmpty {
                                            Text(item.user)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    // Plain buttons only hit-test their label's content. Make
                                    // the whole visible row selectable, including trailing space.
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    #if os(iOS)
                                    .frame(minHeight: 44)
                                    #endif
                                    .contentShape(Rectangle())
                                }
                                // Without this the automatic List button style tints the whole row,
                                // defeating the explicit .primary/.secondary text hierarchy.
                                .buttonStyle(.plain)
                                .accessibilityElement(children: .combine)
                                #if os(macOS)
                                // Hover tooltip so truncated names/usernames are still readable
                                // on the pointer-driven macOS panel.
                                .help(item.name.isEmpty ? item.user : item.name)
                                .contextMenu {
                                    if !item.user.isEmpty {
                                        Button(L("copy_username")) {
                                            let pb = NSPasteboard.general
                                            pb.clearContents()
                                            pb.setString(item.user, forType: .string)
                                        }
                                    }
                                }
                                #endif
                            }
                        }
                    }
                }
            }
        }
    }

    private var filtered: [AutofillSuggestionSnapshot] {
        guard !query.isEmpty else { return model.suggestions }
        return model.suggestions.filter {
            $0.name.localizedStandardContains(query) || $0.user.localizedStandardContains(query)
                || $0.accountName.localizedStandardContains(query)
        }
    }
}

enum AutofillListMode {
    case passwords
    case oneTimeCodes
    case registration
}

/// Loads the matching logins through the shared `GetSuggestions` path.
@MainActor
@Observable
final class AutofillListModel {
    private let core: KeyguardCore
    private let mode: AutofillListMode
    private let serviceIdentifiers: [String]
    private let onPick: (String) -> Void
    private let onCancel: () -> Void

    var title: String {
        switch mode {
        case .passwords, .registration: return L("autofill_passwords_section")
        case .oneTimeCodes: return L("autofill_verification_codes_section")
        }
    }

    @ObservationIgnored private var statusSubscription: KeyguardCancellable?
    @ObservationIgnored private var observationID: UUID?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var didLoad = false

    var isUnlocked = false
    var isLoading = false
    private(set) var isPicking = false
    var loadError: String?
    var suggestions: [AutofillSuggestionSnapshot] = []
    /// `onUnlocked` is a no-op: this model's own status subscription drives the
    /// locked → unlocked transition and the suggestion load.
    let unlockModel: AutofillUnlockModel

    init(
        core: KeyguardCore,
        mode: AutofillListMode,
        serviceIdentifiers: [String],
        onPick: @escaping (String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.core = core
        self.mode = mode
        self.serviceIdentifiers = serviceIdentifiers
        self.onPick = onPick
        self.onCancel = onCancel
        self.unlockModel = AutofillUnlockModel(
            core: core,
            onUnlocked: {},
            onCancel: onCancel
        )
    }

    func start() {
        guard observationID == nil else { return }
        let observationID = UUID()
        self.observationID = observationID
        statusSubscription = core.observeStatus { [weak self] status in
            Task { @MainActor [weak self] in
                guard let self, self.observationID == observationID else { return }
                let unlocked = status == KeyguardVaultStatus.unlocked
                self.isUnlocked = unlocked
                if !unlocked {
                    self.clearSuggestions()
                }
                if unlocked, !self.didLoad {
                    self.didLoad = true
                    self.loadSuggestions()
                }
            }
        }
    }

    func stop() {
        observationID = nil
        statusSubscription?.cancel()
        statusSubscription = nil
        isUnlocked = false
        isPicking = false
        clearSuggestions()
    }

    private func clearSuggestions() {
        loadTask?.cancel()
        loadTask = nil
        didLoad = false
        isLoading = false
        loadError = nil
        suggestions = []
    }

    func retry() {
        guard observationID != nil, isUnlocked, !isLoading else { return }
        loadSuggestions()
    }

    private func loadSuggestions() {
        loadTask?.cancel()
        isLoading = true
        loadError = nil
        loadTask = Task { [weak self, core, mode, serviceIdentifiers] in
            do {
                let result: [AutofillSuggestionSnapshot]
                switch mode {
                case .passwords:
                    result = try await core.loadAutofillSuggestions(
                        serviceIdentifiers: serviceIdentifiers
                    )
                case .registration:
                    result = try await core.loadPasskeyRegistrationSuggestions(serviceIdentifiers: serviceIdentifiers)
                case .oneTimeCodes:
                    result = try await core.loadOneTimeCodeSuggestions(
                        serviceIdentifiers: serviceIdentifiers
                    )
                }
                // The Kotlin operation can finish after its Swift task is cancelled.
                // Never publish results from a previous appearance or vault session.
                guard !Task.isCancelled, let self, self.isUnlocked else { return }
                self.suggestions = result
                self.isLoading = false
                self.loadTask = nil
            } catch {
                guard !Task.isCancelled, let self, self.isUnlocked else { return }
                self.loadError = L("autofill_request_failed")
                self.isLoading = false
                self.loadTask = nil
            }
        }
    }

    func pick(recordId: String) {
        guard observationID != nil, isUnlocked, !isLoading, !isPicking else { return }
        // Resolving a credential can suspend; registration also writes a passkey.
        // Accept only one row activation while that request is in flight.
        isPicking = true
        onPick(recordId)
    }
    func cancel() { onCancel() }
}
