import SwiftUI

struct GpgToolsScreen: View {
    @Environment(SessionFactory.self) private var sessions
    @State private var operation = "encrypt"
    @State private var presentation = FormPresentation<GpgToolsModel>(stop: { $0.close() })

    var body: some View {
        Group {
            if let model = presentation.model {
                GpgToolsView(operation: $operation).environment(model)
            } else {
                LoadingIndicator()
            }
        }
        .onAppear { presentation.start(makeModel: sessions.makeGpgToolsModel) }
        .onDisappear { presentation.close() }
    }
}
