#if os(macOS)
import XCTest
@testable import KeyguardUI

final class AgentSetupWizardStateTests: XCTestCase {
    func testHostValidationAndBackPreserveChoices() {
        var state = AgentSetupWizardState(agent: .ssh(socket: "/tmp/agent.sock"), mode: .configure)
        XCTAssertTrue(state.canContinue)
        state.setOneHost(true)
        for invalid in ["", "*.example.com", "host\nHost *", "host name"] {
            state.setHost(invalid)
            XCTAssertFalse(state.canContinue)
        }
        state.setHost("git.example.com")
        state.setShell("zsh", selected: true)
        state.next()
        XCTAssertEqual(state.step, .run)
        XCTAssertEqual(state.back(), .stay)
        XCTAssertEqual(state.host, "git.example.com")
        XCTAssertEqual(state.selectedShells, ["zsh"])
        XCTAssertEqual(state.back(), .confirmDiscard)
    }

    func testGpgNeedsPreparationAndShellAndUndoDoesNot() {
        var unavailable = AgentSetupWizardState(
            agent: .gpg(home: "/tmp/home", preparationCommand: nil), mode: .configure)
        unavailable.setShell("bash", selected: true)
        XCTAssertFalse(unavailable.canContinue)
        var state = AgentSetupWizardState(
            agent: .gpg(home: "/tmp/home", preparationCommand: "prepare"), mode: .configure)
        XCTAssertFalse(state.canContinue)
        state.setShell("fish", selected: true)
        XCTAssertTrue(state.canContinue)
        var undo = AgentSetupWizardState(agent: .gpg(home: "/tmp/home", preparationCommand: nil), mode: .undo)
        XCTAssertEqual(undo.stepNumber, 1)
        XCTAssertEqual(undo.stepCount, 2)
        XCTAssertTrue(undo.canContinue)
        XCTAssertEqual(undo.command(executable: "/app/keyguard"), "env /app/keyguard agents undo gpg --review")
        undo.next()
        XCTAssertEqual(undo.stepNumber, 2)
    }

    func testCommandsQuotePathsAndPreserveReviewedSelection() {
        var state = AgentSetupWizardState(agent: .ssh(socket: "/tmp/Alice's socket"), mode: .configure)
        state.setOneHost(true)
        state.setHost(" work-server ")
        state.setShell("zsh", selected: true)
        state.setShell("bash", selected: true)
        state.next()
        state.setHost("different-server")
        state.setShell("fish", selected: true)
        XCTAssertEqual(
            state.command(executable: "/Applications/Keyguard App/keyguard"),
            "env '/Applications/Keyguard App/keyguard' agents setup --agent ssh --socket '/tmp/Alice'\\''s socket' --shells bash,zsh --host work-server"
        )
        state.next()
        XCTAssertEqual(state.step, .check)
        XCTAssertFalse(state.needsDiscardConfirmation)
        XCTAssertEqual(state.back(), .dismiss)
    }
}
#endif
