import SwiftUI
import KeyguardShared

struct LicenseView: View {
    @Environment(AppInformationModel.self) private var appInformationModel

    private var snapshot: LicenseListSnapshot { appInformationModel.licenseList }

    var body: some View {
        SnapshotContent(loaded: snapshot.loaded, isEmpty: snapshot.items.isEmpty) {
            ContentUnavailableView {
                Label(L10n.licensesEmptyLabel, systemImage: "doc.text")
            }
        } content: {
            List {
                ForEach(snapshot.items, id: \.id) { item in
                    LicenseItemRow(name: item.name, version: item.version, license: item.license, url: item.url)
                }
            }
            #if os(macOS)
            .alternatingRowBackgrounds()
            #endif
        }
        .observing(
            start: { appInformationModel.startLicenseObservation() },
            stop: { appInformationModel.stopLicenseObservation() }
        )
    }

}
