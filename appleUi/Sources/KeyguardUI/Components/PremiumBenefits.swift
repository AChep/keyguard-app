import SwiftUI

struct PremiumBenefits: View {
    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 12) {
                PremiumBenefitCard(
                    title: L10n.prefItemPremiumMembershipBenefitCreateEditItems,
                    systemImage: "square.and.pencil",
                    colors: [Color(red: 0.08, green: 0.25, blue: 0.63), Color(red: 0.16, green: 0.38, blue: 0.72)]
                )
                .frame(width: 296)
                PremiumBenefitCard(
                    title: L10n.prefItemPremiumMembershipBenefitMultipleAccounts,
                    systemImage: "person.2.fill",
                    colors: [Color(red: 0.03, green: 0.36, blue: 0.39), Color(red: 0.08, green: 0.43, blue: 0.42)]
                )
                .frame(width: 296)
                PremiumBenefitCard(
                    title: L10n.prefItemPremiumMembershipBenefitSupportDevelopment,
                    systemImage: "heart.fill",
                    colors: [Color(red: 0.50, green: 0.12, blue: 0.29), Color(red: 0.64, green: 0.20, blue: 0.32)]
                )
                .frame(width: 296)
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }
}

#Preview {
    Form {
        Section {
            PremiumBenefits()
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
        } footer: {
            Text(L10n.prefItemPremiumMembershipFooter)
        }
    }
    .formStyle(.grouped)
}
