import Observation

/// Retains a form beneath child screens; the presenting route closes it on dismissal.
@MainActor
@Observable
final class FormPresentation<Model> {
    private(set) var model: Model?
    private let stop: (Model) -> Void

    init(stop: @escaping (Model) -> Void) { self.stop = stop }

    func start(makeModel: () -> Model) {
        guard model == nil else { return }
        model = makeModel()
    }

    func close() {
        let previous = model
        model = nil
        if let previous { stop(previous) }
    }
}
