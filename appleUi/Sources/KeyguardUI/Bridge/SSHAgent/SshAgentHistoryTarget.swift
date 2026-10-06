/// A nil cipher means all history, while a nil session target means no observation.
struct SshAgentHistoryTarget: Equatable {
    let cipherId: String?
}
