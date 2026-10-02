import SwiftUI

struct SendView: View {
    @Environment(SessionFactory.self) private var sessions

    var body: some View { SendListScreen(makeSession: sessions.makeSendListSession) }
}
