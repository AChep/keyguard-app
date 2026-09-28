import Foundation
import Observation
import KeyguardShared

/// Imports and mutations shared by the native Wordlist forms.
@MainActor
@Observable
final class WordlistsModel {
    private let core: KeyguardCore
    private let notifications: NotificationsModel

    init(core: KeyguardCore, notifications: NotificationsModel) {
        self.core = core
        self.notifications = notifications
    }

    /// Imports a new wordlist from a local file.
    func addWordlistFromFile(name: String, uri: String) async throws {
        try await core.addWordlistFromFile(name: name, uri: uri)
    }

    /// Imports a new wordlist by downloading it from a URL.
    func addWordlistFromUrl(name: String, url: String) async throws {
        try await core.addWordlistFromUrl(name: name, url: url)
    }

    /// Renames the wordlist with the given id.
    func renameWordlist(id: Int64, name: String) async throws {
        try await core.renameWordlist(id: id, name: name)
    }

    /// Deletes the wordlists with the given ids.
    func deleteWordlists(ids: [Int64], onSuccess: @escaping () -> Void = {}) {
        let boxed = ids.map { KotlinLong(value: $0) }
        notifications.perform {
            try await self.core.deleteWordlists(ids: boxed)
            onSuccess()
        }
    }

}
