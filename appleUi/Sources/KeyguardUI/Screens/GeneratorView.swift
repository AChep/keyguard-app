import SwiftUI

struct GeneratorView: View {
    @Environment(SessionFactory.self) private var sessions

    var body: some View {
        GeneratorScreen(makeSession: sessions.makeGeneratorSession)
    }
}
