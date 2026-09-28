import SwiftUI

/// Compact step identity and progress for the backup setup flow.
struct BackupWizardHeader: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let title: String
    let subtitle: String
    let systemImage: String
    let stepLabel: String
    let step: Int
    let stepCount: Int

    @ScaledMetric(relativeTo: .title2) private var iconSize = 48

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(stepLabel)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 6) {
                    ForEach(0..<max(stepCount, 0), id: \.self) { index in
                        Capsule()
                            .fill(.quaternary)
                            .overlay {
                                if index < step {
                                    Capsule()
                                        .fill(Color.accentColor)
                                }
                            }
                    }
                }
                .frame(height: 4)
                .accessibilityHidden(true)
            }

            titleLayout {
                Image(systemName: systemImage)
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: iconSize, height: iconSize)
                    .background {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(Color.accentColor.opacity(0.12))
                    }
                    .accessibilityHidden(true)

                Text(title)
                    .font(.title2.bold())
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
            }

            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var titleLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
    }
}

#Preview {
    ScrollView {
        BackupWizardHeader(
            title: "Protect your vault",
            subtitle: "Choose where to keep your encrypted backups.",
            systemImage: "externaldrive.badge.checkmark",
            stepLabel: "Step 1 of 4",
            step: 1,
            stepCount: 4
        )
        .padding()
    }
}
