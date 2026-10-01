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

    func addFolder(accountId: String, name: String) async throws {
        try await core.addFolder(accountId: accountId, name: name)
    }

    /// One-shot copy of a vault item's field ("username" / "password" / "otp")
    /// to the clipboard, via the shared `ClipboardService`.
    func copyCipherField(secretId: String, accountId: String, field: String) {
        notifications.perform {
            try await self.core.quickCopyCipherField(secretId: secretId, accountId: accountId, field: field)
        }
    }

    func generateAndCopyPassword() {
        notifications.perform { try await self.core.generateAndCopyPassword() }
    }
}
