import Foundation
import Observation

/// Bundle lookup is explicit because changing AppleLanguages does not invalidate
/// Foundation's cached bundle localization. Observation also refreshes SwiftUI
/// views that read generated L10n accessors when the preference changes.
@Observable
final class AppLocalization {
    // The generated `L10n` accessors are nonisolated (they are read from every
    // isolation domain, including the AutoFill extensions), so this singleton cannot
    // be main-actor isolated. `languageTag` is only ever written from the appearance
    // snapshot on the main actor; everything else reads it.
    nonisolated(unsafe) static let shared = AppLocalization()

    var languageTag: String? {
        didSet {
            // Keep shared Foundation-based resources aligned with the native UI.
            // Apply this before resolving bundles so resetting to the system
            // language cannot read the previous app override.
            if let languageTag {
                UserDefaults.standard.set([languageTag], forKey: "AppleLanguages")
            } else {
                UserDefaults.standard.removeObject(forKey: "AppleLanguages")
            }
        }
    }

    init() {
        // The persisted setting arrives in the appearance snapshot. Until then,
        // resolve the system preference without a previous launch's override.
        UserDefaults.standard.removeObject(forKey: "AppleLanguages")
    }

    var locale: Locale {
        languageTag.map(Locale.init(identifier:)) ?? .autoupdatingCurrent
    }

    var bundle: Bundle {
        let languages = languageTag.map { [$0] } ?? Locale.preferredLanguages
        let supported = Bundle.module.localizations
        let localization =
            Bundle.preferredLocalizations(
                from: supported,
                forPreferences: languages
            ).first ?? "en"
        guard let path = Bundle.module.path(forResource: localization, ofType: "lproj"),
            let bundle = Bundle(path: path)
        else { return .module }
        return bundle
    }
}
