#if DEBUG
import SwiftUI

struct AttachmentPreviewJsonRowsPreview: View {
    @State private var expandedIds: Set<String> = ["root"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(AttachmentPreviewJsonRowsFixtures.rows, id: \.id) { row in
                    AttachmentPreviewJsonRow(row: row, expanded: expandedIds.contains(row.id)) {
                        if expandedIds.contains(row.id) {
                            expandedIds.remove(row.id)
                        } else {
                            expandedIds.insert(row.id)
                        }
                    }
                }
            }
            .padding(24)
        }
    }
}

#Preview("JSON rows · narrow") {
    AttachmentPreviewJsonRowsPreview()
        .frame(width: 320, height: 700)
}

#Preview("JSON rows · large text") {
    AttachmentPreviewJsonRowsPreview()
        .dynamicTypeSize(.accessibility3)
        .frame(width: 390, height: 800)
}
#endif
