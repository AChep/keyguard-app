import KeyguardShared

/// One presentation slot for details and the existing add/edit form.
enum EmailForwarderSheet: Identifiable {
    case detail(id: String)
    case form(EmailRelayFormSnapshot)

    var id: String {
        switch self {
        case let .detail(id): "detail:\(id)"
        case let .form(form): "form:\(form.id ?? form.type)"
        }
    }
}
