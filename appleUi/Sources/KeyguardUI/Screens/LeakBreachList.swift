import Foundation
import SwiftUI
import KeyguardShared

struct LeakBreachList: View {
    let breaches: [LeakBreachSnapshot]
    let breachFoundTitle: String
    let breachNotFoundTitle: String
    let breachSectionTitle: String
    private let breachRows: [LeakBreachListRow]
    private let descriptionHTMLs: [String]
    @State private var attributedDescriptions: [String: AttributedString] = [:]

    init(
        breaches: [LeakBreachSnapshot],
        breachFoundTitle: String,
        breachNotFoundTitle: String,
        breachSectionTitle: String
    ) {
        self.breaches = breaches
        self.breachFoundTitle = breachFoundTitle
        self.breachNotFoundTitle = breachNotFoundTitle
        self.breachSectionTitle = breachSectionTitle

        let breachRows = Self.makeRows(for: breaches)
        self.breachRows = breachRows
        self.descriptionHTMLs = Self.uniqueDescriptionHTMLs(in: breachRows)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if breaches.isEmpty {
                LeakNote(kind: .ok, title: breachNotFoundTitle)
            } else {
                LeakNote(kind: .warning, title: breachFoundTitle)
                Text(breachSectionTitle)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
            }
            ForEach(breachRows) { row in
                if row.showsDivider {
                    Divider()
                }
                LeakBreachRow(
                    breach: row.breach,
                    description: row.description(from: attributedDescriptions)
                )
            }
        }
        .task(id: descriptionHTMLs) {
            await precomputeDescriptions(descriptionHTMLs)
        }
    }

    private static func makeRows(for breaches: [LeakBreachSnapshot]) -> [LeakBreachListRow] {
        let primaryIDs = breaches.map(\.primaryStableIdentity)
        var primaryCounts: [String: Int] = [:]
        for id in primaryIDs {
            primaryCounts[id, default: 0] += 1
        }

        var baseIDOccurrences: [String: Int] = [:]
        return breaches.enumerated().map { index, breach in
            let primaryID = primaryIDs[index]
            // The snapshot has no exported breach id, so use title/domain unless
            // that pair collides, then fall back to the fuller stable content.
            let baseID =
                primaryCounts[primaryID, default: 0] > 1
                ? breach.contentStableIdentity
                : primaryID
            let occurrence = baseIDOccurrences[baseID, default: 0]
            baseIDOccurrences[baseID] = occurrence + 1
            let id =
                occurrence == 0
                ? baseID
                : LeakStableID.make([baseID, String(occurrence)])
            let descriptionHTML = breach.descriptionText.isEmpty ? nil : breach.descriptionText
            return LeakBreachListRow(
                id: id,
                breach: breach,
                showsDivider: index > 0,
                descriptionHTML: descriptionHTML,
                initialDescription: descriptionHTML.map {
                    LeakHTMLAttributedStringCache.shared.initialAttributed(for: $0)
                }
            )
        }
    }

    private static func uniqueDescriptionHTMLs(in rows: [LeakBreachListRow]) -> [String] {
        var seen = Set<String>()
        return rows.compactMap { row in
            guard let descriptionHTML = row.descriptionHTML,
                seen.insert(descriptionHTML).inserted
            else {
                return nil
            }
            return descriptionHTML
        }
    }

    @MainActor
    private func precomputeDescriptions(_ htmls: [String]) async {
        guard !htmls.isEmpty else {
            attributedDescriptions = [:]
            return
        }

        var nextDescriptions = attributedDescriptions.filter { htmls.contains($0.key) }
        for html in htmls {
            guard !Task.isCancelled else { return }
            if let cached = LeakHTMLAttributedStringCache.shared.attributed(for: html) {
                nextDescriptions[html] = cached
                continue
            }

            await Task.yield()
            guard !Task.isCancelled else { return }
            let attributed = LeakHTMLRenderer.attributed(from: html)
            guard !Task.isCancelled else { return }
            LeakHTMLAttributedStringCache.shared.insert(attributed, for: html)
            nextDescriptions[html] = attributed
        }
        guard !Task.isCancelled else { return }
        attributedDescriptions = nextDescriptions
    }
}

private struct LeakBreachListRow: Identifiable {
    let id: String
    let breach: LeakBreachSnapshot
    let showsDivider: Bool
    let descriptionHTML: String?
    let initialDescription: AttributedString?

    func description(from precomputedDescriptions: [String: AttributedString]) -> AttributedString? {
        guard let descriptionHTML else { return nil }
        return precomputedDescriptions[descriptionHTML] ?? initialDescription
    }
}

/// One breached service row.
private struct LeakBreachRow: View {
    let breach: LeakBreachSnapshot
    let description: AttributedString?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 16) {
                FaviconView(
                    url: breach.icon,
                    placeholder: nil,
                    fallbackSymbol: "globe",
                    size: 24
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(breach.title)
                        .font(.headline)
                    Text(breach.domain)
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            if !breach.dataClasses.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(Array(breach.dataClasses), id: \.self) { dataClass in
                        LeakChip(text: dataClass)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                if let countText = breach.countText {
                    Text(countText)
                        .font(.caption.weight(.black))
                }
                if let occurredAt = breach.occurredAt {
                    Text(occurredAt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let reportedAt = breach.reportedAt {
                    Text(reportedAt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let description {
                LeakHTMLText(attributed: description)
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension LeakBreachSnapshot {
    var primaryStableIdentity: String {
        LeakStableID.make([domain, title])
    }

    var contentStableIdentity: String {
        LeakStableID.make([
            domain,
            title,
            descriptionText,
            icon ?? "",
            countText ?? "",
            occurredAt ?? "",
            reportedAt ?? "",
            Array(dataClasses).joined(separator: "\u{1F}"),
        ])
    }
}

private enum LeakStableID {
    static func make(_ components: [String]) -> String {
        components
            .map { "\($0.count):\($0)" }
            .joined(separator: "|")
    }
}

/// A single data-class chip (e.g. "Email addresses", "Passwords"), matching the
/// Compose `FlatTextFieldBadge` info-container look.
struct LeakChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.accentColor.opacity(0.16), in: Capsule())
    }
}

/// The warning / all-clear note shown above the breach list (and as the failure /
/// no-breaches state). Mirrors the Compose `FlatSimpleNote`.
struct LeakNote: View {
    enum Kind { case warning, ok }

    let kind: Kind
    var title: String? = nil
    var text: String? = nil

    private var tint: Color { kind == .warning ? .orange : .green }
    private var symbol: String { kind == .warning ? "exclamationmark.triangle.fill" : "checkmark.circle.fill" }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 2) {
                if let title, !title.isEmpty {
                    Text(title)
                        .font(.callout.weight(.semibold))
                }
                if let text, !text.isEmpty {
                    Text(text)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// Renders the precomputed HIBP breach description. The source arrives as a small
/// HTML fragment (paragraph text with the occasional `<a>` link); conversion is
/// cached by the owning list so this view remains a cheap Text wrapper.
struct LeakHTMLText: View {
    let attributed: AttributedString

    var body: some View {
        Text(attributed)
            .textSelection(.enabled)
            .tint(.accentColor)
    }
}

private enum LeakHTMLRenderer {
    // Foundation's HTML importer uses WebKit and requires the main thread.
    // The list caches each fragment and yields between imports above.
    @MainActor
    static func attributed(from html: String) -> AttributedString {
        let data = Data(html.utf8)
        if let ns = try? NSAttributedString(
            data: data,
            options: [
                .documentType: NSAttributedString.DocumentType.html,
                .characterEncoding: String.Encoding.utf8.rawValue,
            ],
            documentAttributes: nil
        ), var attributed = try? AttributedString(ns, including: \.swiftUI) {
            // The system importer bakes in its own font / size; drop them so the
            // SwiftUI `.font(.footnote)` modifier on the Text wins.
            attributed.font = nil
            return attributed
        }
        return fallbackAttributed(from: html)
    }

    static func fallbackAttributed(from html: String) -> AttributedString {
        // Fall back to a crude tag strip so a malformed fragment still reads.
        let stripped = html.replacingOccurrences(
            of: "<[^>]+>",
            with: "",
            options: .regularExpression
        )
        return AttributedString(stripped)
    }
}

// Every stored property below is read and written under `lock`, which is what makes
// the shared instance safe to touch from any isolation domain.
private final class LeakHTMLAttributedStringCache: @unchecked Sendable {
    static let shared = LeakHTMLAttributedStringCache()

    private let lock = NSLock()
    private let maximumEntryCount = 128
    private var attributedByHTML: [String: AttributedString] = [:]
    private var fallbackByHTML: [String: AttributedString] = [:]
    private var attributedOrder: [String] = []
    private var fallbackOrder: [String] = []

    func initialAttributed(for html: String) -> AttributedString {
        if let attributed = attributed(for: html) {
            return attributed
        }
        if let fallback = fallback(for: html) {
            return fallback
        }

        let fallback = LeakHTMLRenderer.fallbackAttributed(from: html)
        insertFallback(fallback, for: html)
        return fallback
    }

    func attributed(for html: String) -> AttributedString? {
        lock.lock()
        defer { lock.unlock() }
        return attributedByHTML[html]
    }

    func insert(_ attributed: AttributedString, for html: String) {
        lock.lock()
        defer { lock.unlock() }
        if attributedByHTML[html] == nil {
            attributedOrder.append(html)
        }
        attributedByHTML[html] = attributed
        trimAttributedEntries()
    }

    private func fallback(for html: String) -> AttributedString? {
        lock.lock()
        defer { lock.unlock() }
        return fallbackByHTML[html]
    }

    private func insertFallback(_ fallback: AttributedString, for html: String) {
        lock.lock()
        defer { lock.unlock() }
        if fallbackByHTML[html] == nil {
            fallbackOrder.append(html)
        }
        fallbackByHTML[html] = fallback
        trimFallbackEntries()
    }

    private func trimAttributedEntries() {
        while attributedOrder.count > maximumEntryCount {
            attributedByHTML.removeValue(forKey: attributedOrder.removeFirst())
        }
    }

    private func trimFallbackEntries() {
        while fallbackOrder.count > maximumEntryCount {
            fallbackByHTML.removeValue(forKey: fallbackOrder.removeFirst())
        }
    }
}

/// The skeleton placeholder while a breach producer is still loading. Mirrors the
/// Compose `ContentSkeleton` (a single flat item with two shimmer lines).
struct LeakLoadingSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 4)
                .fill(.quaternary)
                .frame(width: 180, height: 14)
            RoundedRectangle(cornerRadius: 4)
                .fill(.quaternary)
                .frame(width: 120, height: 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .redacted(reason: .placeholder)
    }
}

/// The "Powered by haveibeenpwned.com" footnote shown at the bottom of every breach
/// dialog. The snapshot carries the (markdown-link) string; render it as a quiet
/// footnote with the link rendered inline.
struct LeakPoweredByFooter: View {
    let text: String

    var body: some View {
        Text((try? AttributedString(markdown: text)) ?? AttributedString(text))
            .font(.caption2)
            .foregroundStyle(.secondary)
            .tint(.accentColor)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
