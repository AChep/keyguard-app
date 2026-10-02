import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class LoginSessionModelTests: XCTestCase {
    @MainActor
    func testBitwardenFormsKeepDraftsActionsAndTwofaCompletionIndependent() async throws {
        let a = BitwardenSourceProbe()
        let b = BitwardenSourceProbe()
        let first = a.makeModel()
        let second = b.makeModel()
        first.startLoginObservation()
        second.startLoginObservation()
        first.startLoginObservation()
        a.publish(login("first@example.test"))
        b.publish(login("second@example.test"))
        a.requireTwofa()
        try await settle()
        first.startTwofaObservation()
        first.setTwofaCode(text: "123456")
        second.setLoginField(id: "email", text: "changed@example.test")
        XCTAssertEqual(a.loginStarts, 1)
        XCTAssertEqual(a.twofaStarts, 1)
        XCTAssertEqual(a.actions, ["code:123456"])
        XCTAssertEqual(b.actions, ["email:changed@example.test"])
        XCTAssertTrue(first.twofaActive)
        XCTAssertFalse(second.twofaActive)
        XCTAssertEqual(first.login.email.text, "first@example.test")
        XCTAssertEqual(second.login.email.text, "second@example.test")
        // The native session sends parent completion for either password-only or 2FA success.
        a.complete()
        try await settle()
        XCTAssertTrue(first.loginDidSucceed)
        XCTAssertTrue(first.twofaDidSucceed)
        XCTAssertFalse(second.loginDidSucceed)
        first.stopLoginObservation()
        XCTAssertEqual(a.loginCancellations, 1)
        XCTAssertEqual(a.twofaCancellations, 1)
        XCTAssertEqual(a.closes, 1)
        XCTAssertEqual(b.closes, 0)
        XCTAssertEqual(second.login.email.text, "second@example.test")
        second.stopLoginObservation()
    }

    @MainActor
    func testDismissedLoginCannotDeliverQueuedChallengeOrSuccessToReplacement() async throws {
        let old = BitwardenSourceProbe()
        let presentation = FormPresentation<AccountLoginForm>()
        let first = old.makeModel()
        presentation.start { .bitwarden(first) }
        first.startLoginObservation()
        old.publish(login("old@example.test"))
        old.requireTwofa()
        old.complete()
        presentation.close()
        let fresh = BitwardenSourceProbe()
        let next = fresh.makeModel()
        presentation.start { .bitwarden(next) }
        next.startLoginObservation()
        fresh.publish(login("new@example.test"))
        try await settle()
        old.requireTwofa()
        old.complete()
        try await settle()
        XCTAssertEqual(first.login.email.text, "")
        XCTAssertFalse(first.twofaActive)
        XCTAssertFalse(first.loginDidSucceed)
        XCTAssertEqual(next.login.email.text, "new@example.test")
        XCTAssertFalse(next.twofaActive)
        XCTAssertFalse(next.loginDidSucceed)
        XCTAssertEqual(old.closes, 1)
        presentation.close()
    }

    @MainActor
    func testBackingOutOfTwofaPreservesLoginAndRejectsOldChildUpdates() async throws {
        let source = BitwardenSourceProbe()
        let model = source.makeModel()
        model.startLoginObservation()
        source.publish(login("draft@example.test"))
        source.requireTwofa()
        try await settle()
        model.startTwofaObservation()
        model.stopTwofaObservation()
        source.publishTwofa(twofa("old challenge"))
        model.startLoginObservation()
        try await settle()
        XCTAssertEqual(model.login.email.text, "draft@example.test")
        XCTAssertFalse(model.twofaActive)
        XCTAssertNil(model.twofa.emailNote)
        XCTAssertEqual(source.loginStarts, 1)
        XCTAssertEqual(source.loginCancellations, 0)
        model.stopLoginObservation()
    }

    @MainActor
    func testKeePassOwnsWebDavCompletionAndQueuedPickerRequests() async throws {
        let a = KeePassSourceProbe()
        let b = KeePassSourceProbe()
        let first = KeePassLoginModel(source: a)
        let second = KeePassLoginModel(source: b)
        first.startKeePassLoginObservation()
        second.startKeePassLoginObservation()
        first.startKeePassLoginObservation()
        a.publishWebDav(webdav("first"))
        b.publishWebDav(webdav("second"))
        try await settle()
        XCTAssertEqual(a.starts, 1)
        XCTAssertEqual(first.keepassWebDav?.id, "first")
        XCTAssertEqual(second.keepassWebDav?.id, "second")
        first.dismissWebDavSettings()
        a.publishWebDav(webdav("first"))
        try await settle()
        XCTAssertNil(first.keepassWebDav)
        a.publishWebDav(webdav("new"))
        try await settle()
        XCTAssertEqual(first.keepassWebDav?.id, "new")
        a.publishFile?(
            KeePassFilePickerRequest(requestId: "late", kind: .openDocument, mimeTypes: [], suggestedName: nil))
        first.stopKeePassLoginObservation()
        a.complete()
        try await settle()
        XCTAssertNil(first.filePicker.pendingFilePicker)
        XCTAssertNil(first.keepassWebDav)
        XCTAssertFalse(first.keepassDidSucceed)
        XCTAssertEqual(a.closes, 1)
        XCTAssertEqual(b.closes, 0)
        XCTAssertEqual(second.keepassWebDav?.id, "second")
        b.complete()
        try await settle()
        XCTAssertTrue(second.keepassDidSucceed)
        XCTAssertNil(second.keepassWebDav)
        second.stopKeePassLoginObservation()
    }

    @MainActor
    func testCompletionRejectsCallbacksBeforeThePresentationFinishesDismissing() async throws {
        let bitwarden = BitwardenSourceProbe()
        let login = bitwarden.makeModel()
        login.startLoginObservation()
        bitwarden.complete()
        try await settle()
        bitwarden.requireTwofa()
        let keepass = KeePassSourceProbe()
        let database = KeePassLoginModel(source: keepass)
        database.startKeePassLoginObservation()
        keepass.complete()
        try await settle()
        keepass.publishWebDav(webdav("late"))
        keepass.publishFile?(
            KeePassFilePickerRequest(requestId: "late", kind: .openDocument, mimeTypes: [], suggestedName: nil))
        try await settle()
        XCTAssertFalse(login.twofaActive)
        XCTAssertTrue(login.loginDidSucceed)
        XCTAssertNil(database.keepassWebDav)
        XCTAssertNil(database.filePicker.pendingFilePicker)
        XCTAssertTrue(database.keepassDidSucceed)
        XCTAssertEqual(bitwarden.closes, 1)
        XCTAssertEqual(keepass.closes, 1)
        login.stopLoginObservation()
        database.stopKeePassLoginObservation()
    }

    @MainActor
    func testReleasingLoginClosesParentAndTwofaWithoutRetainingTheModel() {
        let source = BitwardenSourceProbe()
        var model: BitwardenLoginModel? = source.makeModel()
        model?.startLoginObservation()
        model?.startTwofaObservation()
        weak var released = model
        model = nil
        XCTAssertNil(released)
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.loginCancellations, 1)
        XCTAssertEqual(source.twofaCancellations, 1)
    }

    private func login(_ email: String) -> LoginSnapshot {
        LoginSnapshot(
            email: TextFieldSnapshot(
                id: "email", text: email, textRevision: 0, placeholder: nil, error: nil, vlType: nil, vlText: nil,
                editable: true),
            password: .companion.empty(id: "password"), clientSecret: nil, regions: [], showCustomEnv: false,
            items: [], isLoading: false, canLogin: true, canRegister: true
        )
    }

    private func twofa(_ note: String) -> TwofaSnapshot {
        TwofaSnapshot(
            providers: [], kind: .email, code: nil, emailNote: note, canResend: false,
            rememberMe: false, rememberMeEnabled: false, fallbackTitle: nil, webVaultUrl: nil,
            primaryActionText: nil, canSubmit: false, isLoading: false)
    }

    private func webdav(_ id: String) -> WebDavSettingsSnapshot {
        WebDavSettingsSnapshot(
            id: id, url: "https://example.test/\(id).kdbx", username: id, password: "draft", errorKind: nil,
            isTestingConnection: false)
    }

    @MainActor
    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
private final class BitwardenSourceProbe: BitwardenLoginSource {
    var publish: (LoginSnapshot) -> Void = { _ in }
    var publishTwofa: (TwofaSnapshot) -> Void = { _ in }
    var complete: () -> Void = {}
    var requireTwofa: () -> Void = {}
    var loginStarts = 0
    var twofaStarts = 0
    var loginCancellations = 0
    var twofaCancellations = 0
    var closes = 0
    var actions: [String] = []

    func makeModel() -> BitwardenLoginModel { BitwardenLoginModel(source: self, openExternalURL: { _ in }) }
    func subscribeLogin(
        onChange: @escaping (LoginSnapshot) -> Void, onClose: @escaping () -> Void,
        onTwofaRequired: @escaping () -> Void
    ) -> BridgeObservation {
        loginStarts += 1
        publish = onChange
        complete = onClose
        requireTwofa = onTwofaRequired
        return BridgeObservation(cancel: { [weak self] in self?.loginCancellations += 1 })
    }
    func subscribeTwofa(onChange: @escaping (TwofaSnapshot) -> Void) -> BridgeObservation {
        twofaStarts += 1
        publishTwofa = onChange
        return BridgeObservation(cancel: { [weak self] in self?.twofaCancellations += 1 })
    }
    func close() { closes += 1 }
    func stopTwofa() {}
    func setLoginField(id: String, text: String) { actions.append("\(id):\(text)") }
    func setTwofaCode(text: String) { actions.append("code:\(text)") }
    func selectLoginRegion(key: String) {}
    func invokeLoginAction(id: String) {}
    func clickLoginRegister() {}
    func submitLogin() {}
    func selectTwofaProvider(key: String) {}
    func toggleTwofaRememberMe(checked: Bool) {}
    func resendTwofaCode() {}
    func submitTwofa() {}
    func submitTwofaYubiKey(token: String) {}
}

@MainActor
private final class KeePassSourceProbe: KeePassLoginSource {
    var publishWebDav: (WebDavSettingsSnapshot?) -> Void = { _ in }
    var publishFile: ((KeePassFilePickerRequest) -> Void)?
    var complete: () -> Void = {}
    var starts = 0
    var closes = 0
    func subscribe(
        onChange: @escaping (KeePassLoginSnapshot) -> Void, onClose: @escaping () -> Void,
        onWebDavChange: @escaping (WebDavSettingsSnapshot?) -> Void
    ) -> BridgeObservation {
        starts += 1
        complete = onClose
        publishWebDav = onWebDavChange
        return BridgeObservation(cancel: {})
    }
    func close() { closes += 1 }
    func selectKeePassTab(key: String) {}
    func selectKeePassLocation(key: String) {}
    func pickKeePassDbFile() {}
    func clearKeePassDbFile() {}
    func pickKeePassKeyFile() {}
    func clearKeePassKeyFile() {}
    func setKeePassPassword(text: String) {}
    func submitKeePassLogin() {}
    func setWebDavField(sessionId: String, id: String, text: String) {}
    func submitWebDavSettings(sessionId: String) {}
    func testWebDavConnection(sessionId: String) {}
    func cancelWebDavSettings() {}
    func setKeePassFilePickerRequestHandler(handler: ((KeePassFilePickerRequest) -> Void)?) { publishFile = handler }
    func resolveKeePassFilePicker(requestId: String, uri: String, name: String?, size: Int64, accessToken: String?) {}
    func cancelKeePassFilePicker(requestId: String) {}
}
