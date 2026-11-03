import KeyguardShared
import SwiftUI

/// Edits a list of tags as Mail-style tokens with an inline text field.
/// Typing suggests the tags that exist in the vault; Return or a comma
/// turns the text into a token.
///
/// Every command of the snapshot takes a string and goes through [send]
/// with its id, the same way the draft's edits do.
struct TagTokenField: View {
    let snapshot: TagsFieldSnapshot
    let send: (_ id: String, _ text: String) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var focused: Bool
    @Namespace private var namespace

    /// Local typing buffer; the Kotlin draft stays the source of truth
    /// through the `bridgedText` reconciliation below.
    @State private var buffer = ""
    /// The last token, armed for removal by a Delete in the empty field.
    @State private var armed = false
    /// The suggestion picked with the arrow keys.
    @State private var highlighted: Int?
    @State private var shakes: [String: Int] = [:]
    @State private var added = 0
    @State private var removed = 0

    private var editable: Bool { snapshot.commitId != nil }

    private var showsSuggestions: Bool {
        editable && focused && !snapshot.suggestions.isEmpty
    }

    private var motion: Animation? {
        reduceMotion ? nil : .spring(duration: 0.3)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FlowLayout(spacing: 6, clampsToWidth: true) {
                ForEach(snapshot.tags, id: \.self) { tag in
                    token(tag)
                }
                if editable {
                    input
                }
            }
            if showsSuggestions {
                Divider()
                FlowLayout(spacing: 6, clampsToWidth: true) {
                    ForEach(Array(snapshot.suggestions.enumerated()), id: \.element.name) { index, suggestion in
                        suggestionChip(suggestion, highlighted: index == highlighted)
                    }
                }
                .transition(.opacity)
            }
        }
        .padding(.vertical, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(motion, value: snapshot.tags)
        .animation(motion, value: showsSuggestions)
        .bridgedText(
            $buffer,
            remote: snapshot.draft.text,
            remoteRevision: snapshot.draft.textRevision,
            send: { send(snapshot.draft.id, $0) }
        )
        .onChange(of: buffer) { _, _ in
            armed = false
        }
        .onChange(of: snapshot.suggestions.map(\.name)) { _, _ in
            highlighted = nil
        }
        .onChange(of: focused) { _, isFocused in
            if !isFocused {
                armed = false
                highlighted = nil
                commit(buffer, feedback: false)
            }
        }
        .sensoryFeedback(.selection, trigger: added)
        .sensoryFeedback(.impact(flexibility: .soft), trigger: removed)
    }

    // MARK: - Tokens

    private func token(_ tag: String) -> some View {
        let isArmed = armed && tag == snapshot.tags.last
        return HStack(spacing: 4) {
            Text(tag)
                .lineLimit(1)
                .truncationMode(.middle)
            if snapshot.removeId != nil {
                Button {
                    remove(tag)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .imageScale(.small)
                        .symbolRenderingMode(.hierarchical)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.tagRemoveContentDescription(tag))
            }
        }
        .font(.subheadline.weight(.medium))
        .foregroundStyle(isArmed ? AnyShapeStyle(Color.white) : AnyShapeStyle(.tint))
        .padding(.leading, 10)
        .padding(.trailing, snapshot.removeId != nil ? 6 : 10)
        .padding(.vertical, 5)
        .background(
            isArmed ? AnyShapeStyle(.tint) : AnyShapeStyle(.tint.opacity(0.15)),
            in: Capsule()
        )
        .contentShape(Capsule())
        .onTapGesture {
            edit(tag)
        }
        .contextMenu {
            if snapshot.editId != nil {
                Button(L10n.edit, systemImage: "pencil") {
                    edit(tag)
                }
            }
            if snapshot.removeId != nil {
                Button(L10n.remove, systemImage: "xmark", role: .destructive) {
                    remove(tag)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tag)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: L10n.edit) {
            edit(tag)
        }
        .accessibilityAction(named: L10n.remove) {
            remove(tag)
        }
        .keyframeAnimator(initialValue: 0.0, trigger: shakes[tag, default: 0]) { content, offset in
            content.offset(x: offset)
        } keyframes: { _ in
            KeyframeTrack {
                CubicKeyframe(-6, duration: 0.05)
                CubicKeyframe(6, duration: 0.07)
                CubicKeyframe(-4, duration: 0.07)
                CubicKeyframe(4, duration: 0.07)
                CubicKeyframe(0, duration: 0.14)
            }
        }
        .matchedGeometryEffect(id: tag.lowercased(), in: namespace)
        .transition(.scale(scale: 0.8).combined(with: .opacity))
    }

    // MARK: - Input

    private var input: some View {
        TextField(snapshot.tags.isEmpty ? L10n.tagsFieldPlaceholder : "", text: $buffer)
            .textFieldStyle(.plain)
            .focused($focused)
            .autocorrectionDisabled()
            #if os(iOS)
        .textInputAutocapitalization(.never)
            #endif
            .submitLabel(.done)
            .onSubmit {
                submit()
                // Keep the keyboard up for the next tag.
                focused = true
            }
            .frame(minWidth: 120)
            .onKeyPress(.downArrow) {
                moveHighlight(1)
            }
            .onKeyPress(.upArrow) {
                moveHighlight(-1)
            }
            .onKeyPress(.tab, phases: .down) { press in
                guard !press.modifiers.contains(.shift), let suggestion = tabSuggestion else {
                    return .ignored
                }
                pick(suggestion.name)
                return .handled
            }
            .onKeyPress(.escape) {
                guard highlighted != nil else { return .ignored }
                highlighted = nil
                return .handled
            }
            .onKeyPress(.delete) {
                guard buffer.isEmpty, let last = snapshot.tags.last else {
                    return .ignored
                }
                if armed {
                    remove(last)
                } else {
                    armed = true
                }
                return .handled
            }
    }

    /// The suggestion Tab accepts: the highlighted one, or the
    /// first one that completes the typed text.
    private var tabSuggestion: TagSuggestionSnapshot? {
        if let highlighted, highlighted < snapshot.suggestions.count {
            return snapshot.suggestions[highlighted]
        }
        guard !buffer.isEmpty else { return nil }
        return snapshot.suggestions.first { suggestion in
            suggestion.name.count > buffer.count
                && suggestion.name.lowercased().hasPrefix(buffer.lowercased())
        }
    }

    private func moveHighlight(_ delta: Int) -> KeyPress.Result {
        let count = snapshot.suggestions.count
        guard showsSuggestions, count > 0 else { return .ignored }
        if let highlighted {
            self.highlighted = (highlighted + delta + count) % count
        } else {
            highlighted = delta > 0 ? 0 : count - 1
        }
        return .handled
    }

    // MARK: - Suggestions

    private func suggestionChip(_ suggestion: TagSuggestionSnapshot, highlighted: Bool) -> some View {
        Button {
            pick(suggestion.name)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "plus")
                    .imageScale(.small)
                    .foregroundStyle(.secondary)
                Text(highlightedName(suggestion))
                    .lineLimit(1)
                if suggestion.usage > 0 {
                    Text(suggestion.usage, format: .number)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .font(.subheadline)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(
                highlighted ? AnyShapeStyle(.tint.opacity(0.15)) : AnyShapeStyle(.quaternary),
                in: Capsule()
            )
            .overlay {
                if highlighted {
                    Capsule().strokeBorder(.tint, lineWidth: 1)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(suggestion.name)
        .accessibilityValue(L10n.tagItemsCountPlural(Int(suggestion.usage)))
        .matchedGeometryEffect(id: suggestion.name.lowercased(), in: namespace)
    }

    /// The suggestion's name with the part that matches the draft in bold.
    private func highlightedName(_ suggestion: TagSuggestionSnapshot) -> AttributedString {
        var text = AttributedString(suggestion.name)
        let utf16 = suggestion.name.utf16
        let start = Int(suggestion.matchStart)
        let end = Int(suggestion.matchEnd)
        guard start >= 0, end <= utf16.count, start < end,
            let lower = String.Index(utf16.index(utf16.startIndex, offsetBy: start), within: suggestion.name),
            let upper = String.Index(utf16.index(utf16.startIndex, offsetBy: end), within: suggestion.name),
            let range = Range(lower..<upper, in: text)
        else {
            return text
        }
        text[range].font = .subheadline.weight(.semibold)
        text[range].foregroundColor = .accentColor
        return text
    }

    // MARK: - Commands

    private func submit() {
        if let highlighted, highlighted < snapshot.suggestions.count {
            pick(snapshot.suggestions[highlighted].name)
        } else {
            commit(buffer)
        }
    }

    private func commit(_ text: String, feedback: Bool = true) {
        guard let commitId = snapshot.commitId else { return }
        let tag = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tag.isEmpty else { return }
        // The tag is already there: point at it instead of adding a duplicate.
        if let existing = snapshot.tags.first(where: { $0.caseInsensitiveCompare(tag) == .orderedSame }) {
            if !reduceMotion {
                shakes[existing, default: 0] += 1
            }
            buffer = ""
            return
        }
        send(commitId, text)
        buffer = ""
        if feedback { added += 1 }
    }

    private func pick(_ name: String) {
        commit(name)
    }

    private func remove(_ tag: String) {
        guard let removeId = snapshot.removeId else { return }
        armed = false
        send(removeId, tag)
        removed += 1
    }

    private func edit(_ tag: String) {
        guard let editId = snapshot.editId else { return }
        send(editId, tag)
        focused = true
    }
}
