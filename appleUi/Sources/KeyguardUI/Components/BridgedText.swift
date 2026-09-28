import SwiftUI

extension View {
    func bridgedText(
        _ local: Binding<String>,
        remote: String,
        remoteRevision: Int32,
        send: @escaping (String) -> Void
    ) -> some View {
        self
            .onChange(of: local.wrappedValue) { _, new in
                // The remote snapshot can lag behind an earlier edit. Even
                // returning to its text must update the canonical input.
                send(new)
            }
            .onChange(of: remoteRevision) { _, _ in
                if remote != local.wrappedValue {
                    local.wrappedValue = remote
                }
            }
            // Same-revision updates are echoes of user edits. In particular,
            // an empty buffer may be an intentional deletion, so never refill
            // it from a delayed echo. Restores and prefills change the revision.
            .onAppear {
                local.wrappedValue = remote
            }
    }
}
