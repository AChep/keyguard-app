import SwiftUI
import KeyguardShared

struct SubscriptionsSettingsView: View {
    @Environment(SubscriptionsModel.self) private var subscriptionsModel
    /// Navigation title. Supplied by the settings sub-route (its category title);
    /// defaults to the subscriptions header when pushed from the vault-list paywall
    /// CTA via the navigation stack.
    var title: String = L10n.settingsSubscriptionsHeaderTitle

    private var s: SubscriptionsSnapshot { subscriptionsModel.subscriptions }

    var body: some View {
        form
            .navigationTitle(title)
            .observing(
                start: { subscriptionsModel.startSubscriptionsObservation() },
                stop: { subscriptionsModel.stopSubscriptionsObservation() }
            )
    }

    @ViewBuilder
    private var form: some View {
        Form {
            if s.isPremium {
                Section {
                    Label(L10n.prefItemPremiumMembershipActiveTitle, systemImage: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                }
            }

            Section {
                PremiumBenefits()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    #if os(iOS)
                .listRowSeparator(.hidden)
                    #endif
            } footer: {
                Text(L10n.prefItemPremiumMembershipFooter)
            }

            if s.storeAvailable && !s.loaded {
                ProgressView()
            }
            if s.storeAvailable, let error = s.billing.catalogError ?? s.billing.entitlementError {
                Section {
                    Text(error).foregroundStyle(.secondary)
                    Button(L10n.retry) { subscriptionsModel.refreshBilling() }
                }
            }
            if let message = s.billing.message {
                Section { Text(message) }
            }
            if s.billing.busy { ProgressView() }

            if s.storeAvailable && !s.subscriptions.isEmpty {
                Section(L10n.prefItemPremiumMembershipSectionSubscriptionsTitle) {
                    ForEach(s.subscriptions, id: \.id) { sub in
                        subscriptionRow(sub)
                    }
                }
            }

            if s.storeAvailable && !s.products.isEmpty {
                Section(L10n.premiumPurchaseOneTimeTitle) {
                    ForEach(s.products, id: \.id) { product in
                        productRow(product)
                    }
                }
            }

            if s.storeAvailable {
                Section {
                    Button(L10n.premiumPurchaseRestoreAction) { subscriptionsModel.restorePurchases() }
                    Button(L10n.premiumPurchaseManageSubscriptionAction) { subscriptionsModel.manageSubscriptions() }
                } footer: {
                    Text(L10n.premiumPurchaseRestoreNote)
                }
                .disabled(s.billing.busy)

                Section {
                    if let url = URL(string: "https://gist.github.com/AChep/1fd4e019a4ad8f9647ba3b4694b5dc1c") {
                        Link(L10n.prefItemPrivacyPolicyTitle, destination: url)
                    }
                    if let url = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/") {
                        Link(L10n.premiumPurchaseTermsTitle, destination: url)
                    }
                } footer: {
                    Text(L10n.premiumPurchaseStorekitRenewalNote)
                }
            } else {
                Section { Text(L10n.prefItemPremiumMembershipDirectNote) }
            }

            AppleLicenseSection(snapshot: s.license, storeAvailable: s.storeAvailable)

        }
        .formStyle(.grouped)
    }

    @ViewBuilder
    private func subscriptionRow(_ sub: SubscriptionItemSnapshot) -> some View {
        Button {
            if !sub.active { subscriptionsModel.purchase(id: sub.id) }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(sub.title)
                        .foregroundStyle(.primary)
                    Text(L10n.premiumPurchasePricePeriod(sub.price, sub.periodFormatted))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if !sub.active, let trial = sub.trialPeriodFormatted, !trial.isEmpty {
                        Text(L10n.prefItemPremiumStatusFreeTrialN(trial))
                            .font(.caption2)
                            .foregroundStyle(.tint)
                    }
                }
                Spacer(minLength: 8)
                statusTrailing(active: sub.active, nonRenewing: sub.renewalKnown && !sub.willRenew)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(sub.active || s.billing.busy || !s.billing.entitlementsLoaded || s.billing.entitlementError != nil)
    }

    @ViewBuilder
    private func productRow(_ product: ProductItemSnapshot) -> some View {
        Button {
            if !product.active { subscriptionsModel.purchase(id: product.id) }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(product.title)
                        .foregroundStyle(.primary)
                    Text(product.price)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                statusTrailing(active: product.active)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(
            product.active || s.billing.busy || !s.billing.entitlementsLoaded || s.billing.entitlementError != nil)
    }

    @ViewBuilder
    private func statusTrailing(active: Bool, nonRenewing: Bool = false) -> some View {
        if active {
            VStack(alignment: .trailing, spacing: 1) {
                Label(L10n.accountActionTfaActiveStatus, systemImage: "checkmark.circle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.green)
                if nonRenewing {
                    Text(L10n.prefItemPremiumStatusWillNotRenew)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            Image(systemName: "chevron.forward")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
    }
}
