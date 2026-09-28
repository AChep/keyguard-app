import KeyguardShared

extension GeneratorHistoryItemSnapshot {
    var colorize: Bool {
        switch type {
        case "USERNAME", "EMAIL", "EMAIL_RELAY": false
        default: true
        }
    }
}
