import Foundation
import Observation

/// The main iPad generator moves its form between two layouts. Keep unacknowledged
/// edits above those layouts; mounting a field is never itself an edit.
@MainActor
@Observable
final class GeneratorEditingState {
    private struct Draft {
        var text: String
        let revision: Int32
    }

    private var drafts: [String: Draft] = [:]
    private(set) var focusedKey: String?
    @ObservationIgnored private var mounts: [String: UUID] = [:]

    func text(key: String, remote: String, revision: Int32) -> String {
        guard let draft = drafts[key], draft.revision == revision else { return remote }
        return draft.text
    }

    func reconcile(key: String, remote: String, revision: Int32) {
        guard drafts[key]?.revision != revision else { return }
        drafts[key] = Draft(text: remote, revision: revision)
    }

    func edit(key: String, text: String, revision: Int32) {
        drafts[key] = Draft(text: text, revision: revision)
    }

    func mount(key: String, token: UUID) {
        mounts[key] = token
    }

    func unmount(key: String, token: UUID) {
        if mounts[key] == token { mounts[key] = nil }
    }

    func focusChanged(key: String, token: UUID, focused: Bool) {
        guard mounts[key] == token else { return }
        if focused {
            focusedKey = key
        } else if focusedKey == key {
            focusedKey = nil
        }
    }

    /// Invalidate disappearing fields before changing layout. Their late blur
    /// callbacks must not clear the focus that the replacement field restores.
    func prepareForRemount() {
        mounts.removeAll()
    }

    func endFocus() {
        prepareForRemount()
        focusedKey = nil
    }

    func retain(keys: Set<String>) {
        drafts = drafts.filter { keys.contains($0.key) }
        mounts = mounts.filter { keys.contains($0.key) }
        if let focusedKey, !keys.contains(focusedKey) { self.focusedKey = nil }
    }

    func reset() {
        focusedKey = nil
        drafts.removeAll()
    }
}
