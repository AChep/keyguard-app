import Observation
import KeyguardShared

/// Creates sign-in models; all mutable form state lives in the returned presentation.
@MainActor
@Observable
final class AccountLoginModel {
    private let core: KeyguardCore
    private let links: LinkOpeningCoordinator

    init(core: KeyguardCore, links: LinkOpeningCoordinator) {
        self.core = core
        self.links = links
    }

    func makeForm(kind: AddAccountKind, requestId: String?) -> AccountLoginForm {
        switch kind {
        case .bitwarden:
            let session = core.makeBitwardenLoginSession(requestId: requestId)
            return .bitwarden(
                BitwardenLoginModel(
                    source: session,
                    dialogs: DialogsModel(core: core, formDialogs: session.dialogs),
                    openExternalURL: { [links] in links.open($0, forceSystem: true) }
                ))
        case .keepass:
            return .keepass(KeePassLoginModel(source: core.makeKeePassLoginSession()))
        }
    }
}
