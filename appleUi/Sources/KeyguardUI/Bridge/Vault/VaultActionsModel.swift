import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class VaultActionsModel {
    private let core: KeyguardCore
    private let notifications: NotificationsModel

    init(core: KeyguardCore, notifications: NotificationsModel) {
        self.core = core
        self.notifications = notifications
    }

    /// The core used by this app’s independently owned list sessions.
    var keyguardCore: KeyguardCore { core }

    /// Creates a folder in the given account (the folders screen's native add).
    func addFolder(accountId: String, name: String) async throws {
        try await core.addFolder(accountId: accountId, name: name)
    }

    /// Renames the folder with the given id (the folders screen's native rename).
    func renameFolder(id: String, name: String) async throws {
        try await core.renameFolder(id: id, name: name)
    }

    /// Deletes the folder with the given id (the folders screen's native delete).
    func deleteFolder(id: String, trashCiphers: Bool) {
        notifications.perform { try await self.core.deleteFolder(id: id, trashCiphers: trashCiphers) }
    }

    /// One-shot copy of a vault item's field ("username" / "password" / "otp")
    /// to the clipboard, via the shared `ClipboardService`. Used by the popover.
    func copyCipherField(secretId: String, accountId: String, field: String) {
        notifications.perform {
            try await self.core.quickCopyCipherField(secretId: secretId, accountId: accountId, field: field)
        }
    }

    /// One-shot quick-generate: generates a strong password and copies it.
    func generateAndCopyPassword() {
        notifications.perform { try await self.core.generateAndCopyPassword() }
    }
}
