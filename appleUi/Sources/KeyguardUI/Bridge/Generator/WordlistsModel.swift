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

    func addWordlistFromFile(name: String, uri: String) async throws {
        try await core.addWordlistFromFile(name: name, uri: uri)
    }

    func addWordlistFromUrl(name: String, url: String) async throws {
        try await core.addWordlistFromUrl(name: name, url: url)
    }

    func renameWordlist(id: Int64, name: String) async throws {
        try await core.renameWordlist(id: id, name: name)
    }

    func deleteWordlists(ids: [Int64], onSuccess: @escaping () -> Void = {}) {
        let boxed = ids.map { KotlinLong(value: $0) }
        notifications.perform {
            try await self.core.deleteWordlists(ids: boxed)
            onSuccess()
        }
    }

}
