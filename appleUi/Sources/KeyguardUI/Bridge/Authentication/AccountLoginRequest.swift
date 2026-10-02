import Foundation

struct AccountLoginRequest: Identifiable {
    let id = UUID()
    let kind: AddAccountKind
    let requestId: String?
}
