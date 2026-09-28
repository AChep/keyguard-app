import SwiftUI
import KeyguardShared

struct DetailAlertAppearance {
    let severity: VaultAlertSeverity

    var color: Color {
        if severity == .error { return .red }
        if severity == .warning { return .orange }
        return .blue
    }

    var symbol: String {
        if severity == .error { return "exclamationmark.octagon.fill" }
        if severity == .warning { return "exclamationmark.triangle.fill" }
        return "info.circle.fill"
    }

    var accessibilityLabel: String {
        severity == .info ? L10n.info : L10n.warning
    }
}
