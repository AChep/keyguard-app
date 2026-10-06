import SwiftUI

/// Editorial treatment for a premium benefit, separate from purchase controls.
struct PremiumBenefitCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorSchemeContrast) private var contrast

    let title: String
    let systemImage: String
    let colors: [Color]

    var body: some View {
        let layout =
            dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))

        layout {
            Text(title)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: systemImage)
                .font(.title2.weight(.medium))
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .symbolRenderingMode(.hierarchical)
                .frame(width: 44, height: 44)
                .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)
        }
        .foregroundStyle(.white)
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 20)
                .fill(
                    LinearGradient(
                        colors: contrast == .increased ? [colors[0]] : colors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
        }
        .accessibilityElement(children: .combine)
    }
}
