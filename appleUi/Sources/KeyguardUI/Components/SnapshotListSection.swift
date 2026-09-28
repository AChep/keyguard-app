/// Converts shared flat lists with section markers into native list sections.
/// Empty markers are omitted; rows before the first marker retain an unheaded section.
struct SnapshotListSection<Item>: Identifiable {
    enum ID: Hashable {
        case leading
        case marker(String, occurrence: Int)
    }

    let id: ID
    let title: String?
    var items: [Item]
}

func snapshotListSections<Item>(
    _ items: [Item],
    id: (Item) -> String,
    sectionTitle: (Item) -> String?
) -> [SnapshotListSection<Item>] {
    var sections: [SnapshotListSection<Item>] = []
    var current = SnapshotListSection<Item>(id: .leading, title: nil, items: [])
    var occurrences: [String: Int] = [:]
    for item in items {
        if let title = sectionTitle(item) {
            if !current.items.isEmpty {
                sections.append(current)
            }
            let markerID = id(item)
            let occurrence = occurrences[markerID, default: 0]
            occurrences[markerID] = occurrence + 1
            current = SnapshotListSection(
                id: .marker(markerID, occurrence: occurrence),
                title: title,
                items: []
            )
        } else {
            current.items.append(item)
        }
    }
    if !current.items.isEmpty {
        sections.append(current)
    }
    return sections
}
