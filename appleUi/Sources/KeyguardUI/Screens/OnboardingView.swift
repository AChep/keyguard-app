import SwiftUI
import KeyguardShared

struct OnboardingView: View {
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ModalSheet(title: L10n.featHeaderTitle, width: 560, height: 640) {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(Self.sections) { section in
                        VStack(alignment: .leading, spacing: 12) {
                            if let title = section.title {
                                Text(title)
                                    .font(.headline)
                                    .foregroundStyle(.secondary)
                                    .textCase(.uppercase)
                            }
                            grid(section.items)
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        } actions: {
            Button(L10n.ok) { dismiss() }
                .keyboardShortcut(.defaultAction)
        }
        // Visiting the onboarding marks it done, as the Compose `OnboardingScreen` does.
        .onAppear { authModel.markOnboarded() }
    }

    @ViewBuilder
    private func grid(_ items: [OnboardingItem]) -> some View {
        CardGridLayout {
            ForEach(items) { item in
                card(item)
            }
        }
    }

    @ViewBuilder
    private func card(_ item: OnboardingItem) -> some View {
        ZStack(alignment: .topTrailing) {
            Image(systemName: item.symbol)
                .font(.system(size: 56))
                .foregroundStyle(.primary.opacity(0.05))
                .padding(8)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text(item.title)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                Text(item.text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if item.premium {
                    Label(L10n.featKeyguardPremiumLabel, systemImage: "crown.fill")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tint)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Static content

    struct OnboardingItem: Identifiable {
        let id: String
        let title: String
        let text: String
        var premium: Bool = false
        let symbol: String
    }

    struct OnboardingSection: Identifiable {
        let id: String
        var title: String? = nil
        let items: [OnboardingItem]
    }

    /// The feature catalog, kept in lock-step with the Compose `onboardingSections`
    /// (same items, order and premium flags; SF Symbols stand in for the Material
    /// icons). The premium section has no header in Compose, so it is left untitled.
    // Resolve labels on each read so an in-app language change refreshes the
    // catalog instead of retaining the first locale used in this process.
    static var sections: [OnboardingSection] {
        [
            OnboardingSection(
                id: "premium",
                items: [
                    OnboardingItem(
                        id: "two_way_sync",
                        title: L10n.featItemTwoWaySyncTitle,
                        text: L10n.featItemTwoWaySyncText,
                        premium: true,
                        symbol: "arrow.triangle.2.circlepath"
                    ),
                    OnboardingItem(
                        id: "multiple_accounts",
                        title: L10n.featItemMultipleAccountsTitle,
                        text: L10n.featItemMultipleAccountsText,
                        premium: true,
                        symbol: "person.crop.square"
                    ),
                    OnboardingItem(
                        id: "offline_editing",
                        title: L10n.featItemOfflineEditingTitle,
                        text: L10n.featItemOfflineEditingText,
                        premium: true,
                        symbol: "bolt.slash"
                    ),
                ]
            ),
            OnboardingSection(
                id: "search",
                title: L10n.featSectionSearchTitle,
                items: [
                    OnboardingItem(
                        id: "search_by_anything",
                        title: L10n.featItemSearchByAnythingTitle,
                        text: L10n.featItemSearchByAnythingText,
                        symbol: "magnifyingglass"
                    ),
                    OnboardingItem(
                        id: "filter",
                        title: L10n.featItemFilterTitle,
                        text: L10n.featItemFilterText,
                        symbol: "line.3.horizontal.decrease.circle"
                    ),
                    OnboardingItem(
                        id: "multiple_keywords",
                        title: L10n.featItemMultipleKeywordsTitle,
                        text: L10n.featItemMultipleKeywordsText,
                        symbol: "text.magnifyingglass"
                    ),
                ]
            ),
            OnboardingSection(
                id: "watchtower",
                title: L10n.featSectionWatchtowerTitle,
                items: [
                    OnboardingItem(
                        id: "pwned_passwords",
                        title: L10n.featItemPwnedPasswordsTitle,
                        text: L10n.featItemPwnedPasswordsText,
                        symbol: "exclamationmark.shield"
                    ),
                    OnboardingItem(
                        id: "password_strength",
                        title: L10n.featItemPasswordStrengthTitle,
                        text: L10n.featItemPasswordStrengthText,
                        symbol: "key"
                    ),
                    OnboardingItem(
                        id: "reused_passwords",
                        title: L10n.featItemReusedPasswordsTitle,
                        text: L10n.featItemReusedPasswordsText,
                        symbol: "arrow.triangle.2.circlepath.circle"
                    ),
                    OnboardingItem(
                        id: "inactive_totp",
                        title: L10n.featItemInactiveTotpTitle,
                        text: L10n.featItemInactiveTotpText,
                        symbol: "clock.badge.exclamationmark"
                    ),
                    OnboardingItem(
                        id: "unsecure_websites",
                        title: L10n.featItemUnsecureWebsitesTitle,
                        text: L10n.featItemUnsecureWebsitesText,
                        symbol: "globe.badge.chevron.backward"
                    ),
                    OnboardingItem(
                        id: "incomplete_items",
                        title: L10n.featItemIncompleteItemsTitle,
                        text: L10n.featItemIncompleteItemsText,
                        symbol: "text.alignleft"
                    ),
                    OnboardingItem(
                        id: "expiring_items",
                        title: L10n.featItemExpiringItemsTitle,
                        text: L10n.featItemExpiringItemsText,
                        symbol: "timer"
                    ),
                    OnboardingItem(
                        id: "duplicate_items",
                        title: L10n.featItemDuplicateItemsTitle,
                        text: L10n.featItemDuplicateItemsText,
                        symbol: "doc.on.doc"
                    ),
                ]
            ),
            OnboardingSection(
                id: "misc",
                title: L10n.featSectionMiscTitle,
                items: [
                    OnboardingItem(
                        id: "export",
                        title: L10n.featItemExportTitle,
                        text: L10n.featItemExportText,
                        symbol: "square.and.arrow.down"
                    ),
                    OnboardingItem(
                        id: "multi_selection",
                        title: L10n.featItemMultiSelectionTitle,
                        text: L10n.featItemMultiSelectionText,
                        symbol: "checklist"
                    ),
                    OnboardingItem(
                        id: "show_barcode",
                        title: L10n.featItemShowBarcodeTitle,
                        text: L10n.featItemShowBarcodeText,
                        symbol: "qrcode"
                    ),
                    OnboardingItem(
                        id: "generator",
                        title: L10n.featItemGeneratorTitle,
                        text: L10n.featItemGeneratorText,
                        symbol: "dial.min"
                    ),
                ]
            ),
        ]
    }
}
