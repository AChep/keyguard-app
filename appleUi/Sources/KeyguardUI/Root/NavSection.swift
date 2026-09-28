import SwiftUI
import KeyguardShared

struct NavSection: Identifiable, Hashable {
    /// `vault`, `sends`, … or `cipher_filter:<id>`; stable identity + selection tag.
    let key: String
    /// The navigation-stack scope this section renders (`setNavScope` +
    /// `NavStackContainer`). Built-ins keep the pre-existing scope names
    /// (`send`, `gpg_tools`); cipher filters use `cipher_filter:<id>`.
    let scope: String
    let isCipherFilter: Bool
    let title: String
    let systemImage: String
    /// Non-empty for cipher-filter sections only.
    let filterId: String

    var id: String { key }

    init(snapshot: NavSectionSnapshot) {
        key = snapshot.key
        scope = snapshot.scope
        isCipherFilter = snapshot.isCipherFilter
        filterId = snapshot.filterId
        if snapshot.isCipherFilter {
            title = snapshot.title
            systemImage = NavSection.symbol(forIconHint: snapshot.iconHint)
        } else {
            title = NavSection.builtInTitle(key: snapshot.key)
            systemImage = NavSection.builtInSymbol(key: snapshot.key)
        }
    }

    private init(builtInKey: String, scope: String) {
        key = builtInKey
        self.scope = scope
        isCipherFilter = false
        filterId = ""
        title = NavSection.builtInTitle(key: builtInKey)
        systemImage = NavSection.builtInSymbol(key: builtInKey)
    }

    /// Pre-snapshot fallback (and the locked-shell placeholder): the built-in
    /// sections in their default order, matching `NavItemsConfigDefaults`.
    static var defaults: [NavSection] {
        [
            NavSection(builtInKey: "vault", scope: "vault"),
            NavSection(builtInKey: "sends", scope: "send"),
            NavSection(builtInKey: "generator", scope: "generator"),
            NavSection(builtInKey: "gpg_tools", scope: "gpg_tools"),
            NavSection(builtInKey: "watchtower", scope: "watchtower"),
            NavSection(builtInKey: "settings", scope: "settings"),
        ]
    }

    /// The built-in keys are the shared `NavItemsConfigDefaults` constants.
    static func builtInTitle(key: String) -> String {
        switch key {
        case "vault": return L10n.homeVaultLabel
        case "sends": return L10n.homeSendLabel
        case "generator": return L10n.homeGeneratorLabel
        case "gpg_tools": return L10n.gpgToolsHeaderTitle
        case "watchtower": return L10n.homeWatchtowerLabel
        case "settings": return L10n.homeSettingsLabel
        default: return key
        }
    }

    static func builtInSymbol(key: String) -> String {
        switch key {
        case "vault": return "lock.fill"
        case "sends": return "paperplane.fill"
        case "generator": return "dice.fill"
        case "gpg_tools": return "lock.rectangle.on.rectangle"
        case "watchtower": return "checkmark.shield.fill"
        case "settings": return "gearshape.fill"
        default: return "square.grid.2x2"
        }
    }

    /// SF Symbols for the Kotlin cipher-filter icon hints (the predefined
    /// type filters); user-saved filters carry no hint and get the generic
    /// filter glyph.
    static func symbol(forIconHint hint: String) -> String {
        switch hint {
        case "login": return "person.badge.key"
        case "card": return "creditcard"
        case "identity": return "person.text.rectangle"
        case "note": return "note.text"
        case "sshKey": return "terminal"
        case "gpgKey": return "key"
        case "otp": return "clock"
        default: return "line.3.horizontal.decrease.circle"
        }
    }
}

/// The feature view of a top-level section. Cipher-filter sections render a
/// filtered vault list; `.id(section.key)` keeps each filter tab's state its
/// own when the selection moves between two filter sections.
@MainActor
@ViewBuilder
func navSectionDetail(_ section: NavSection) -> some View {
    switch section.key {
    case "vault":
        HomeView()
    case "sends":
        SendView()
    case "generator":
        GeneratorView()
    case "gpg_tools":
        GpgToolsView()
    case "watchtower":
        WatchtowerView()
    case "settings":
        SettingsView()
    default:
        CipherFilterTabView(section: section)
            .id(section.key)
    }
}
