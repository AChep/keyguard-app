import SwiftUI

struct AddAccountDestination: View {
    @Environment(AccountLoginModel.self) private var factory
    let kind: AddAccountKind
    var requestId: String? = nil
    let presentation: FormPresentation<AccountLoginForm>

    var body: some View {
        Group {
            switch presentation.model {
            case .bitwarden(let model): BitwardenLoginView().environment(model)
            case .keepass(let model): KeePassLoginView().environment(model)
            case nil: LoadingIndicator()
            }
        }
        .onAppear { presentation.start { factory.makeForm(kind: kind, requestId: requestId) } }
    }
}
