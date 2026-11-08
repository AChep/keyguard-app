import SwiftUI
import KeyguardShared

struct ListItemActionButton: View {
    let action: VaultActionSnapshot
    /// Swipe actions show danger as a tint: the destructive role animates the
    /// row out before the producer's confirmation dialog appears.
    var swipeAction = false
    let invoke: (String) -> Void

    // These list producers supply stable semantic action ids; the last component
    // picks a fallback icon.
    private var kind: Substring? { action.id.split(separator: ".").last }

    private var iconName: String? {
        if let iconName = action.iconName { return iconName }
        if action.isCopy { return "doc.on.doc" }
        switch kind {
        case "edit": return "pencil"
        case "duplicate": return "plus.square.on.square"
        case "delete", "remove": return "trash"
        case "show", "showAndLock": return action.id.contains(":largeType.") ? "textformat.size" : nil
        case "checkPasswordLeak": return "checkmark.shield"
        default: return nil
        }
    }

    var body: some View {
        if swipeAction {
            Button {
                invoke(action.id)
            } label: {
                label
            }
            .tint(action.danger ? Color(platform: .platformDanger) : nil)
        } else {
            Button(role: action.danger ? .destructive : nil) {
                invoke(action.id)
            } label: {
                label
            }
        }
    }

    @ViewBuilder
    private var label: some View {
        if let iconName {
            Label(action.title, systemImage: iconName)
        } else {
            Text(action.title)
        }
    }
}
