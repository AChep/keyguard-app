import UIKit

/// Receives only the owned loopback bookmark. It does not open an SSH connection.
@main
@MainActor
final class ReceiverApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let label = UILabel()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        label.text = "E2E SSH URL receiver"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.font = .preferredFont(forTextStyle: .body)
        label.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            label.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor),
        ])
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        if let url = launchOptions?[.url] as? URL {
            return receive(url)
        }
        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        receive(url)
    }

    private func receive(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.scheme == "ssh", components.host == "127.0.0.1", components.port == 2222,
            components.user == "e2e", components.password == nil,
            components.path.isEmpty || components.path == "/",
            components.query == nil, components.fragment == nil
        else { return false }
        do {
            let directory = try FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true
            )
            let receipt = directory.appendingPathComponent("ssh-url-receipt.json")
            let data = try JSONSerialization.data(
                withJSONObject: [
                    "url": url.absoluteString,
                    "receivedAtEpoch": Date().timeIntervalSince1970,
                    "processId": ProcessInfo.processInfo.processIdentifier,
                ], options: [.sortedKeys])
            try data.write(to: receipt, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: receipt.path)
            label.text = "Received owned SSH URL\n" + url.absoluteString
            return true
        } catch {
            label.text = "Could not record the owned SSH URL"
            return false
        }
    }
}
