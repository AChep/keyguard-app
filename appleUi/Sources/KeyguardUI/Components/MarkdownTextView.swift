import SwiftUI
// MarkdownUI predates strict concurrency; its `Theme`/`TextStyle` values are
// immutable but unannotated.
@preconcurrency import MarkdownUI

struct MarkdownTextView: View {
    let text: String

    var body: some View {
        Markdown(text)
            .markdownTheme(.keyguard)
            .textSelection(.enabled)
    }
}

/// `.basic` with fenced code restyled to the app's chip look (`.quaternary`
/// rounded rect, as in `TotpBadgeView`) — the basic theme draws code blocks
/// as bare indented text, which gets lost inside the grouped form rows.
private extension Theme {
    // Builds SwiftUI views in `codeBlock`, so it belongs on the main actor. The three
    // residual `IsolatedConformances` warnings below come from MarkdownUI's own
    // pre-concurrency DSL types and cannot be resolved from this side.
    @MainActor static let keyguard = Theme.basic
        .codeBlock { configuration in
            ScrollView(.horizontal) {
                configuration.label
                    .relativeLineSpacing(.em(0.25))
                    .markdownTextStyle {
                        FontFamilyVariant(.monospaced)
                        FontSize(.em(0.94))
                    }
                    .padding(10)
            }
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            .markdownMargin(top: .zero, bottom: .em(0.8))
        }
}
