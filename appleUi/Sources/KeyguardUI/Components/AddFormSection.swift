import KeyguardShared

/// Section markers come from the shared cipher / Send producer. Use their keys,
/// rather than positions or localized titles, to preserve field state when a
/// preceding section is inserted or removed.
struct AddFormSection: Identifiable {
    let id: String?
    let title: String?
    var items: [AddItemSnapshot]

    static func sections(from items: [AddItemSnapshot]) -> [AddFormSection] {
        var sections: [AddFormSection] = []
        var current = AddFormSection(id: nil, title: nil, items: [])
        for item in items {
            if item.kind == .section {
                if !current.items.isEmpty {
                    sections.append(current)
                }
                current = AddFormSection(id: item.id, title: item.title, items: [])
            } else {
                current.items.append(item)
            }
        }
        if !current.items.isEmpty {
            sections.append(current)
        }
        return sections
    }
}
