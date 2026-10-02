@MainActor
enum AccountLoginForm {
    case bitwarden(BitwardenLoginModel)
    case keepass(KeePassLoginModel)

    func close() {
        switch self {
        case .bitwarden(let model): model.stopLoginObservation()
        case .keepass(let model): model.stopKeePassLoginObservation()
        }
    }
}

extension FormPresentation where Model == AccountLoginForm {
    convenience init() { self.init(stop: { $0.close() }) }
}
