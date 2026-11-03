#if DEBUG
import KeyguardShared

enum AttachmentPreviewJsonRowsFixtures {
    static var rows: [AttachmentPreviewJsonNodeSnapshot] {
        [
            row("root", key: "$", type: .object, count: 6),
            row("object", key: "account", type: .object, count: 2),
            row("array", key: "recoveryCodes", type: .array, count: 10),
            row("string", key: "username", type: .string, value: "\"demo@example.com\""),
            row("number", key: "attempts", type: .number, value: "12.50"),
            row("boolean", key: "enabled", type: .boolean, value: "true"),
            row("null", key: "previous", type: .null, value: "null"),
            row("empty", key: "metadata", type: .object),
            longRow,
        ]
    }

    static var longRow: AttachmentPreviewJsonNodeSnapshot {
        row(
            "long", key: String(repeating: "Long property name / довга назва / 長い名前 ", count: 6),
            type: .string, value: "\"" + String(repeating: "Long value / довге значення / 長い値 ", count: 8) + "\"",
            depth: 5, parentPath: "$ / accounts / [0] / settings / nested"
        )
    }

    static func row(
        _ id: String, key: String, type: AttachmentPreviewJsonTypeSnapshot,
        value: String? = nil, count: Int32 = 0, depth: Int32 = 1, parentPath: String = "$"
    ) -> AttachmentPreviewJsonNodeSnapshot {
        AttachmentPreviewJsonNodeSnapshot(
            id: id, depth: depth, key: key, parentPath: parentPath, type: type,
            value: value, childCount: count, expandable: count > 0
        )
    }
}
#endif
