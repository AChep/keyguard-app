import XCTest
import KeyguardShared
@testable import KeyguardUI

final class AddFormSectionTests: XCTestCase {
    func testEveryEditableRowAndActionRemainsInProducerOrder() {
        let items = [
            item("name", .title), item("password", .password),
            item("uris", .section, title: "URIs"), item("uri", .url), item("add-uri", .add),
            item("fields", .section, title: "Fields"), item("custom", .fieldText),
            item("toggle", .fieldSwitch), item("linked", .fieldLinkedId),
            item("notes", .note), item("expiration", .dateTime), item("file", .attachment),
        ]
        let sections = AddFormSection.sections(from: items)
        XCTAssertEqual(sections.map(\.id), [nil, "uris", "fields"])
        XCTAssertEqual(sections.map(\.title), [nil, "URIs", "Fields"])
        XCTAssertEqual(
            sections.flatMap(\.items).map(\.id),
            items.filter { $0.kind != .section }.map(\.id)
        )
    }

    func testSectionIdentitySurvivesInsertionRemovalAndLocalization() {
        let original = [item("fields", .section, title: "Fields"), item("custom", .fieldText)]
        let inserted = [item("uris", .section), item("uri", .url)] + original
        XCTAssertEqual(AddFormSection.sections(from: original).last?.id, "fields")
        XCTAssertEqual(AddFormSection.sections(from: inserted).last?.id, "fields")
        let translated = [item("fields", .section, title: "Поля"), item("custom", .fieldText)]
        XCTAssertEqual(AddFormSection.sections(from: translated).last?.id, "fields")
    }

    func testEmptySectionsDoNotProduceOrphanHeaders() {
        XCTAssertTrue(AddFormSection.sections(from: []).isEmpty)
        let sections = AddFormSection.sections(from: [
            item("empty", .section, title: "Empty"),
            item("unnamed", .section), item("note", .note),
            item("trailing", .section, title: "Trailing"),
        ])
        XCTAssertEqual(sections.map(\.id), ["unnamed"])
        XCTAssertNil(sections.first?.title)
        XCTAssertEqual(sections.first?.items.map(\.id), ["note"])
    }

    private func item(_ id: String, _ kind: AddItemKind, title: String? = nil) -> AddItemSnapshot {
        AddItemSnapshot(
            id: id, kind: kind, title: title, text: nil, fields: [],
            switchValue: false, switchEnabled: false, switchId: nil,
            enumValue: nil, dateText: nil, timeText: nil,
            attachment: nil, sshKey: nil, gpgKey: nil, passkeyName: nil,
            totpScanId: nil, options: [], actions: []
        )
    }
}
