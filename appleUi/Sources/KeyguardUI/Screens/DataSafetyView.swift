import SwiftUI
import KeyguardShared

struct DataSafetyView: View {
    @Environment(AppInformationModel.self) private var appInformationModel
    @State private var items: [DataSafetyItemSnapshot] = []
    @State private var loaded = false

    var body: some View {
        ScrollView {
            if loaded {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(items, id: \.id) { item in
                        row(item)
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                LoadingIndicator()
                    .padding(.top, 80)
            }
        }
        .task {
            items = await appInformationModel.loadDataSafety()
            loaded = true
        }
    }

    @ViewBuilder
    private func row(_ item: DataSafetyItemSnapshot) -> some View {
        switch item.kind {
        case DataSafetyItemKind.largeSection:
            Text(item.text)
                .font(.title3.weight(.semibold))
                .padding(.top, 12)
        case DataSafetyItemKind.section:
            Text(item.text)
                .font(.headline)
                .padding(.top, 6)
        case DataSafetyItemKind.text:
            Text(item.text)
                .font(.body)
                .foregroundStyle(item.secondary ? .secondary : .primary)
                .fixedSize(horizontal: false, vertical: true)
        case DataSafetyItemKind.row:
            HStack(alignment: .top, spacing: 16) {
                Text(item.title)
                    .fontWeight(.medium)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(item.value)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .font(.callout)
            .foregroundStyle(item.secondary ? .secondary : .primary)
        case DataSafetyItemKind.learnMore:
            if let url = item.url, let link = URL(string: url) {
                Link(item.text, destination: link)
                    .padding(.top, 4)
            }
        default:
            EmptyView()
        }
    }
}
