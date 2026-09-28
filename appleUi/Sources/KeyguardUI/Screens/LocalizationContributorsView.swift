import SwiftUI
import KeyguardShared

struct LocalizationContributorsView: View {
    @Environment(AppInformationModel.self) private var appInformationModel

    private var snapshot: LocalizationContributorsSnapshot { appInformationModel.localizationContributors }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.localizationContributorsEmptyLabel, systemImage: "person.2")
            }
        } content: {
            List {
                ForEach(snapshot.items, id: \.id) { item in
                    row(item)
                }
            }
            #if os(macOS)
            .alternatingRowBackgrounds()
            #endif
        }
        .observing(
            start: { appInformationModel.startLocalizationContributorsObservation() },
            stop: { appInformationModel.stopLocalizationContributorsObservation() }
        )
    }

    private func row(_ item: LocalizationContributorItemSnapshot) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle")
                .foregroundStyle(.tint)
                .frame(width: 24)
            Text(item.name)
            Spacer(minLength: 0)
            Text("\(item.score)")
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}
