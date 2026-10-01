import Observation
import KeyguardShared

/// Column visibility must not own list observation or reset a list's scroll state.
@MainActor
@Observable
final class NavigationListSessions {
    private(set) var models: [Int64: VaultListSessionModel] = [:]

    func update(entries: [ScreenEntrySnapshot], scope: String) {
        let lists = entries.filter { $0.kind == .vaultList }
        for id in Set(models.keys).subtracting(lists.map(\.instanceId)) {
            models.removeValue(forKey: id)?.stop()
        }
        for entry in lists where models[entry.instanceId] == nil {
            guard let session = entry.vaultListSession else { continue }
            let model = VaultListSessionModel(session: session)
            model.setNavigationOrigin(
                ListNavigationOrigin(scope: scope, listEntryId: KotlinLong(value: entry.instanceId)))
            models[entry.instanceId] = model
            model.start()
        }
    }

    func stop() {
        for model in models.values { model.stop() }
        models = [:]
    }
}
