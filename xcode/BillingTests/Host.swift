import SwiftUI

/// Isolated host for StoreKitTest; never opens the user's vault or registers app workers.
@main
struct BillingTestHost: App {
    var body: some Scene {
        WindowGroup { Color.clear.frame(width: 100, height: 100) }
    }
}
