import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class HistorySessionModelTests: XCTestCase {
    @MainActor
    func testPasswordHistoryPresentationsForTheSameItemKeepSelectionAndLifetimeIndependent() async throws {
        let firstSource = HistoryProbe<PasswordHistorySnapshot>()
        let secondSource = HistoryProbe<PasswordHistorySnapshot>()
        let first = passwordModel { _ in firstSource }
        let second = passwordModel { _ in secondSource }
        first.setTarget("same cipher")
        second.setTarget("same cipher")
        let firstOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        XCTAssertNotEqual(firstOwner, secondOwner)
        firstSource.publish?(password("first", selected: true))
        secondSource.publish?(password("second"))
        try await settle()
        XCTAssertEqual(first.detail?.selectionCount, 1)
        XCTAssertEqual(second.detail?.selectionCount, 0)
        first.perform(owner: firstOwner) { $0.actions.append("remove selection") }
        first.stop()
        first.perform(owner: firstOwner) { $0.actions.append("late clear") }
        second.perform(owner: secondOwner) { $0.actions.append("copy") }
        secondSource.publish?(password("updated second"))
        try await settle()
        XCTAssertNil(first.detail)
        XCTAssertEqual(second.detail?.items.first?.value, "updated second")
        XCTAssertEqual(firstSource.actions, ["remove selection"])
        XCTAssertEqual(secondSource.actions, ["copy"])
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(secondSource.cancellations, 0)
        second.stop()
    }

    @MainActor
    func testPasswordHistoryReplacementClearsRowsAndRejectsOldActionsAndQueuedSnapshots() async throws {
        var sources: [HistoryProbe<PasswordHistorySnapshot>] = []
        let model = passwordModel { _ in
            let source = HistoryProbe<PasswordHistorySnapshot>()
            sources.append(source)
            return source
        }
        model.setTarget("A")
        let oldOwner = try XCTUnwrap(model.identity)
        sources[0].publish?(password("A", selected: true))
        model.setTarget("B")
        XCTAssertNil(model.detail)
        XCTAssertNotEqual(model.identity, oldOwner)
        try await settle()
        XCTAssertNil(model.detail)
        sources[1].publish?(password("B"))
        sources[0].publish?(password("late A", selected: true))
        model.perform(owner: oldOwner) { $0.actions.append("stale delete") }
        try await settle()
        XCTAssertEqual(model.detail?.items.first?.value, "B")
        XCTAssertEqual(model.detail?.selectionCount, 0)
        XCTAssertTrue(sources[1].actions.isEmpty)
        sources[1].publish?(.companion.empty)
        try await settle()
        XCTAssertEqual(model.detail, .companion.empty)
        sources[1].publish?(password("queued before close"))
        model.stop()
        try await settle()
        XCTAssertNil(model.detail)
        XCTAssertEqual(sources.map(\.cancellations), [1, 1])
    }

    @MainActor
    func testAllSshHistoryIsAnActiveTargetAndDoesNotReplaceCipherHistory() async throws {
        var targets: [SshAgentHistoryTarget] = []
        var sources: [HistoryProbe<SshAgentHistorySnapshot>] = []
        let makeSession: (SshAgentHistoryTarget) -> HistoryProbe<SshAgentHistorySnapshot> = { target in
            targets.append(target)
            let source = HistoryProbe<SshAgentHistorySnapshot>()
            sources.append(source)
            return source
        }
        let all = sshModel(makeSession)
        let cipher = sshModel(makeSession)
        all.setTarget(SshAgentHistoryTarget(cipherId: nil))
        cipher.setTarget(SshAgentHistoryTarget(cipherId: "A"))
        XCTAssertEqual(targets.map(\.cipherId), [nil, "A"])
        XCTAssertNotNil(all.identity)
        sources[0].publish?(ssh("all"))
        sources[1].publish?(ssh("A"))
        try await settle()
        XCTAssertEqual(all.detail?.subtitle, "all")
        XCTAssertEqual(cipher.detail?.subtitle, "A")
        all.stop()
        sources[0].publish?(ssh("late all"))
        sources[1].publish?(ssh("updated A"))
        try await settle()
        XCTAssertNil(all.detail)
        XCTAssertEqual(cipher.detail?.subtitle, "updated A")
        XCTAssertEqual(sources.map(\.cancellations), [1, 0])
        cipher.stop()
    }

    @MainActor
    func testSwitchingSshHistoryToAllStartsANewSessionAndRejectsOldFilterUpdates() async throws {
        var sources: [HistoryProbe<SshAgentHistorySnapshot>] = []
        let model = sshModel { _ in
            let source = HistoryProbe<SshAgentHistorySnapshot>()
            sources.append(source)
            return source
        }
        model.setTarget(SshAgentHistoryTarget(cipherId: "A"))
        let oldOwner = model.identity
        sources[0].publish?(ssh("queued A"))
        model.setTarget(SshAgentHistoryTarget(cipherId: nil))
        model.setTarget(SshAgentHistoryTarget(cipherId: nil))
        XCTAssertNotEqual(model.identity, oldOwner)
        XCTAssertEqual(sources.count, 2)
        try await settle()
        XCTAssertNil(model.detail)
        sources[1].publish?(ssh("all"))
        sources[0].publish?(ssh("late A"))
        try await settle()
        XCTAssertEqual(model.detail?.subtitle, "all")
        sources[1].publish?(.companion.empty)
        try await settle()
        XCTAssertEqual(model.detail, .companion.empty)
        sources[1].publish?(ssh("unlocked"))
        try await settle()
        XCTAssertEqual(model.detail?.subtitle, "unlocked")
        model.setTarget(nil)
        XCTAssertNil(model.detail)
        XCTAssertEqual(sources.map(\.cancellations), [1, 1])
    }

    @MainActor
    func testGeneratorHistoryPresentationsForTheSameItemKeepSelectionAndLifetimeIndependent() async throws {
        let firstSource = HistoryProbe<GeneratorHistorySnapshot>()
        let secondSource = HistoryProbe<GeneratorHistorySnapshot>()
        let first = generatorModel { _ in firstSource }
        let second = generatorModel { _ in secondSource }
        first.setTarget(true)
        second.setTarget(true)
        let firstOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        XCTAssertNotEqual(firstOwner, secondOwner)
        firstSource.publish?(generator("first", selected: true))
        secondSource.publish?(generator("second"))
        try await settle()
        XCTAssertEqual(first.detail?.selectionCount, 1)
        XCTAssertEqual(second.detail?.selectionCount, 0)
        first.perform(owner: firstOwner) { $0.actions.append("remove selection") }
        first.stop()
        first.perform(owner: firstOwner) { $0.actions.append("late clear") }
        second.perform(owner: secondOwner) { $0.actions.append("copy") }
        secondSource.publish?(generator("updated second"))
        try await settle()
        XCTAssertNil(first.detail)
        XCTAssertEqual(second.detail?.items.first?.title, "updated second")
        XCTAssertEqual(firstSource.actions, ["remove selection"])
        XCTAssertEqual(secondSource.actions, ["copy"])
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(secondSource.cancellations, 0)
        second.stop()
    }

    @MainActor
    func testGeneratorHistoryReplacementClearsRowsAndRejectsOldActionsAndQueuedSnapshots() async throws {
        var sources: [HistoryProbe<GeneratorHistorySnapshot>] = []
        let model = generatorModel { _ in
            let source = HistoryProbe<GeneratorHistorySnapshot>()
            sources.append(source)
            return source
        }
        model.setTarget(true)
        let oldOwner = try XCTUnwrap(model.identity)
        sources[0].publish?(generator("A", selected: true))
        model.stop()
        model.setTarget(true)
        XCTAssertNil(model.detail)
        XCTAssertNotEqual(model.identity, oldOwner)
        try await settle()
        XCTAssertNil(model.detail)
        sources[1].publish?(generator("B"))
        sources[0].publish?(generator("late A", selected: true))
        model.perform(owner: oldOwner) { $0.actions.append("stale delete") }
        try await settle()
        XCTAssertEqual(model.detail?.items.first?.title, "B")
        XCTAssertEqual(model.detail?.selectionCount, 0)
        XCTAssertTrue(sources[1].actions.isEmpty)
        sources[1].publish?(.companion.empty)
        try await settle()
        XCTAssertEqual(model.detail, .companion.empty)
        sources[1].publish?(generator("queued before close"))
        model.stop()
        try await settle()
        XCTAssertNil(model.detail)
        XCTAssertEqual(sources.map(\.cancellations), [1, 1])
    }

    @MainActor
    func testUrlRulesPresentationsForTheSameItemKeepSelectionAndLifetimeIndependent() async throws {
        let firstSource = HistoryProbe<UrlRuleListSnapshot>()
        let secondSource = HistoryProbe<UrlRuleListSnapshot>()
        let first = urlRulesModel { _ in firstSource }
        let second = urlRulesModel { _ in secondSource }
        first.setTarget(true)
        second.setTarget(true)
        let firstOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        XCTAssertNotEqual(firstOwner, secondOwner)
        firstSource.publish?(urlRules("first", selected: true))
        secondSource.publish?(urlRules("second"))
        try await settle()
        XCTAssertEqual(first.detail?.selectionCount, 1)
        XCTAssertEqual(second.detail?.selectionCount, 0)
        first.perform(owner: firstOwner) { $0.actions.append("remove selection") }
        first.stop()
        first.perform(owner: firstOwner) { $0.actions.append("late clear") }
        second.perform(owner: secondOwner) { $0.actions.append("copy") }
        secondSource.publish?(urlRules("updated second"))
        try await settle()
        XCTAssertNil(first.detail)
        XCTAssertEqual(second.detail?.items.first?.title, "updated second")
        XCTAssertEqual(firstSource.actions, ["remove selection"])
        XCTAssertEqual(secondSource.actions, ["copy"])
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(secondSource.cancellations, 0)
        second.stop()
    }

    @MainActor
    func testUrlRulesReplacementClearsRowsAndRejectsOldActionsAndQueuedSnapshots() async throws {
        var sources: [HistoryProbe<UrlRuleListSnapshot>] = []
        let model = urlRulesModel { _ in
            let source = HistoryProbe<UrlRuleListSnapshot>()
            sources.append(source)
            return source
        }
        model.setTarget(true)
        let oldOwner = try XCTUnwrap(model.identity)
        sources[0].publish?(urlRules("A", selected: true))
        model.stop()
        model.setTarget(true)
        XCTAssertNil(model.detail)
        XCTAssertNotEqual(model.identity, oldOwner)
        try await settle()
        XCTAssertNil(model.detail)
        sources[1].publish?(urlRules("B"))
        sources[0].publish?(urlRules("late A", selected: true))
        model.perform(owner: oldOwner) { $0.actions.append("stale delete") }
        try await settle()
        XCTAssertEqual(model.detail?.items.first?.title, "B")
        XCTAssertEqual(model.detail?.selectionCount, 0)
        XCTAssertTrue(sources[1].actions.isEmpty)
        sources[1].publish?(.companion.empty)
        try await settle()
        XCTAssertEqual(model.detail, .companion.empty)
        sources[1].publish?(urlRules("queued before close"))
        model.stop()
        try await settle()
        XCTAssertNil(model.detail)
        XCTAssertEqual(sources.map(\.cancellations), [1, 1])
    }

    @MainActor
    private func passwordModel(
        _ makeSession: @escaping (String) -> HistoryProbe<PasswordHistorySnapshot>
    ) -> DetailSessionModel<String, PasswordHistorySnapshot, HistoryProbe<PasswordHistorySnapshot>> {
        DetailSessionModel(makeSession: makeSession, subscribe: { $0.subscribe($1) })
    }

    @MainActor
    private func sshModel(
        _ makeSession: @escaping (SshAgentHistoryTarget) -> HistoryProbe<SshAgentHistorySnapshot>
    ) -> DetailSessionModel<SshAgentHistoryTarget, SshAgentHistorySnapshot, HistoryProbe<SshAgentHistorySnapshot>> {
        DetailSessionModel(makeSession: makeSession, subscribe: { $0.subscribe($1) })
    }

    private func password(_ value: String, selected: Bool = false) -> PasswordHistorySnapshot {
        PasswordHistorySnapshot(
            loaded: true, notFound: false,
            items: [
                PasswordHistoryItemSnapshot(
                    id: "row", value: value, date: nil, monospace: false, actions: [],
                    selected: selected, selecting: selected)
            ],
            selectionCount: selected ? 1 : 0, selectionActions: [], actions: [])
    }

    private func ssh(_ title: String) -> SshAgentHistorySnapshot {
        SshAgentHistorySnapshot(loaded: true, subtitle: title, items: [])
    }

    @MainActor
    private func generatorModel(
        _ makeSession: @escaping (Bool) -> HistoryProbe<GeneratorHistorySnapshot>
    ) -> DetailSessionModel<Bool, GeneratorHistorySnapshot, HistoryProbe<GeneratorHistorySnapshot>> {
        DetailSessionModel(makeSession: makeSession, subscribe: { $0.subscribe($1) })
    }

    @MainActor
    private func urlRulesModel(
        _ makeSession: @escaping (Bool) -> HistoryProbe<UrlRuleListSnapshot>
    ) -> DetailSessionModel<Bool, UrlRuleListSnapshot, HistoryProbe<UrlRuleListSnapshot>> {
        DetailSessionModel(makeSession: makeSession, subscribe: { $0.subscribe($1) })
    }

    private func generator(_ value: String, selected: Bool = false) -> GeneratorHistorySnapshot {
        GeneratorHistorySnapshot(
            loaded: true,
            items: [
                GeneratorHistoryItemSnapshot(
                    id: "row", kind: .value, title: value, date: nil, type: "PASSWORD", actions: [],
                    selected: selected, selecting: selected)
            ],
            options: [], selectionCount: selected ? 1 : 0, selectionActions: [])
    }

    private func urlRules(_ value: String, selected: Bool = false) -> UrlRuleListSnapshot {
        UrlRuleListSnapshot(
            loaded: true,
            items: [
                UrlRuleItemSnapshot(
                    id: "row", title: value, subtitle: "", detail: "", active: true, actions: [],
                    selected: selected, selecting: selected)
            ],
            hasPrimaryAction: true, selectionCount: selected ? 1 : 0, selectionActions: [])
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(20))
    }
}

@MainActor
private final class HistoryProbe<Snapshot> {
    var publish: ((Snapshot) -> Void)?
    var actions: [String] = []
    var cancellations = 0

    func subscribe(_ callback: @escaping (Snapshot) -> Void) -> BridgeObservation {
        publish = callback
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }
}
