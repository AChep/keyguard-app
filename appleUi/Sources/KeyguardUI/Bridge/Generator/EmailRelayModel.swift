import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class EmailRelayModel {
    private let core: KeyguardCore
    private let notifications: NotificationsModel

    init(core: KeyguardCore, notifications: NotificationsModel) {
        self.core = core
        self.notifications = notifications
    }

    /// Loads the catalog of available forwarder services (each a blank form).
    func loadEmailRelayServices() async throws -> [EmailRelayFormSnapshot] {
        try await core.loadEmailRelayServices()
    }

    /// Loads an existing forwarder as a prefilled form, or nil if unknown.
    func loadEmailRelay(id: String) async throws -> EmailRelayFormSnapshot? {
        try await core.loadEmailRelay(id: id)
    }

    /// Creates (id == nil) or updates an email forwarder.
    func saveEmailRelay(id: String?, type: String, name: String, values: [String: String]) async throws {
        try await core.saveEmailRelay(id: id, type: type, name: name, values: values)
    }

    func duplicateEmailRelay(id: String) {
        notifications.perform { try await self.core.duplicateEmailRelay(id: id) }
    }

    func deleteEmailRelays(ids: [String]) {
        notifications.perform { try await self.core.deleteEmailRelays(ids: ids) }
    }

}
