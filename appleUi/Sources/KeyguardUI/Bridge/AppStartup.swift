import Observation
import KeyguardShared

/// All scenes wait for storage before creating the process-wide core or its workers.
@MainActor
@Observable
public final class AppStartup {
    public static let shared = AppStartup()

    public private(set) var model: AppViewModel?
    private(set) var errorKey: String?

    private init() {}

    public func start() {
        guard model == nil else { return }
        errorKey = KeyguardStorage.shared.prepare()
        guard errorKey == nil else { return }
        model = AppViewModel.create()
    }
}
