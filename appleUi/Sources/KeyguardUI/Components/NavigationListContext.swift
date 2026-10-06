import SwiftUI
import KeyguardShared

/// Browse selection is independent of the list's bulk-edit selection.
struct NavigationListContext: Equatable, Sendable {
    struct Detail: Equatable, Sendable {
        let instanceId: Int64
        let cipherId: String?
        let cipherAccountId: String?
        let fromList: Bool

        init(_ entry: ScreenEntrySnapshot) {
            instanceId = entry.instanceId
            cipherId = entry.cipherId
            cipherAccountId = entry.cipherAccountId
            fromList = entry.fromList
        }

        @MainActor
        func selectedRowId(in store: VaultRowStore) -> String? {
            guard let cipherId, let cipherAccountId else { return nil }
            return store.firstItemRowId { $0.secretId == cipherId && $0.accountId == cipherAccountId }
        }
    }

    let scope: String
    let listEntryId: Int64?
    let detail: Detail?
    let isActive: Bool

    var origin: ListNavigationOrigin {
        ListNavigationOrigin(scope: scope, listEntryId: listEntryId.map { KotlinLong(value: $0) })
    }
}

private struct NavigationListContextKey: EnvironmentKey {
    static let defaultValue: NavigationListContext? = nil
}

extension EnvironmentValues {
    var navigationListContext: NavigationListContext? {
        get { self[NavigationListContextKey.self] }
        set { self[NavigationListContextKey.self] = newValue }
    }
}
