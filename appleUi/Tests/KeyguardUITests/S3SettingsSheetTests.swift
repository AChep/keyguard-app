#if os(iOS)
import SwiftUI
import UIKit
import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class S3SettingsSheetTests: XCTestCase {
    func testInvalidSubmissionRevealsAndFocusesSecretOnPhoneAndPad() async throws {
        for size in [CGSize(width: 390, height: 844), CGSize(width: 834, height: 1194)] {
            let source = KeePassSourceProbe()
            let model = KeePassLoginModel(source: source)
            model.startKeePassLoginObservation()
            source.publishS3(snapshot())
            try await Task.sleep(for: .milliseconds(30))
            let host = UIHostingController(rootView: content(model))
            let window = makeWindow(host, size: size)
            defer { window.isHidden = true; model.stopKeePassLoginObservation() }
            await settle(host.view)
            source.publishS3(snapshot(request: 1, error: true))
            await settle(host.view)
            let secret = try field("secretAccessKey", in: host.view)
            XCTAssertTrue(secret.isFirstResponder)
            XCTAssertTrue(secret.isSecureTextEntry)
            XCTAssertTrue(secret.convert(secret.bounds, to: window).intersects(window.bounds))
            // Move focus elsewhere, then submit the same error again.
            let accessKey = try field("accessKeyId", in: host.view)
            XCTAssertTrue(accessKey.becomeFirstResponder())
            source.publishS3(snapshot(request: 2, error: true))
            await settle(host.view)
            XCTAssertTrue(secret.isFirstResponder, "Repeated Save must reveal the same invalid field")
        }
    }

    func testCorrectingVisibleErrorPreservesSecureFieldDraftAndFocus() async throws {
        let source = KeePassSourceProbe()
        let model = KeePassLoginModel(source: source)
        model.startKeePassLoginObservation()
        source.publishS3(snapshot())
        try await Task.sleep(for: .milliseconds(30))
        let host = UIHostingController(rootView: content(model))
        let window = makeWindow(host, size: CGSize(width: 834, height: 1194))
        defer { window.isHidden = true; model.stopKeePassLoginObservation() }
        await settle(host.view)
        source.publishS3(snapshot(request: 1, error: true))
        await settle(host.view)
        let secret = try field("secretAccessKey", in: host.view)
        secret.insertText("QaSecret2026!")
        await settle(host.view)
        source.publishS3(snapshot(request: 1, error: false))
        await settle(host.view)
        let corrected = try field("secretAccessKey", in: host.view)
        XCTAssertTrue(corrected === secret)
        XCTAssertTrue(corrected.isFirstResponder)
        XCTAssertTrue(corrected.isSecureTextEntry)
        XCTAssertEqual(corrected.text, "QaSecret2026!")
    }

    private func content(_ model: KeePassLoginModel) -> some View {
        S3SettingsSheet(sessionId: "s3")
            .environment(model)
            .environment(
                NotificationsModel(
                    observeMessages: { _ in BridgeObservation(cancel: {}) },
                    scheduleDismiss: { _, _ in BridgeObservation(cancel: {}) }))
    }

    private func snapshot(request: Int32 = 0, error: Bool = false) -> S3SettingsSnapshot {
        S3SettingsSnapshot(
            id: "s3", endpoint: "", region: "", bucket: "vaults", key: "vault.kdbx", accessKeyId: "AKID",
            secretAccessKey: "", pathStyle: true, errorKind: error ? "SecretAccessKeyRequired" : nil,
            isTestingConnection: false,
            fieldErrors: error
                ? [S3SettingsFieldErrorSnapshot(id: "secretAccessKey", kind: "SecretAccessKeyRequired")] : [],
            validationRequest: request, validationField: error ? "secretAccessKey" : nil)
    }

    private func makeWindow(_ host: UIViewController, size: CGSize) -> UIWindow {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = host
        window.makeKeyAndVisible()
        return window
    }

    private func field(_ id: String, in view: UIView) throws -> UITextField {
        let fields = descendants(view).compactMap { $0 as? UITextField }
        return try XCTUnwrap(
            fields.first {
                id == "secretAccessKey" ? $0.isSecureTextEntry : $0.placeholder == L10n.s3SettingsAccessKeyIdTitle
            })
    }

    private func descendants(_ view: UIView) -> [UIView] { view.subviews.flatMap { [$0] + descendants($0) } }

    private func settle(_ view: UIView) async {
        view.setNeedsLayout()
        view.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(500))
        view.layoutIfNeeded()
    }
}
#endif
