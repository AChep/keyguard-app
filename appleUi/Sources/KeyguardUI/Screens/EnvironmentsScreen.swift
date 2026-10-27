import SwiftUI
import KeyguardShared

struct EnvironmentsScreen: View {
    var environmentId: String?
    var create = false

    var body: some View {
        EnvironmentsModelHost(title: L10n.environmentsTitle) { model in
            EnvironmentsView(model: model)
                .task(id: model.snapshot?.unlocked) {
                    guard model.snapshot?.unlocked == true else { return }
                    if let environmentId {
                        model.session.open(id: environmentId)
                    } else if create {
                        model.session.create()
                    }
                }
        }
    }
}

/// Owns an environments session while the screen is shown, so each pushed screen has its own.
struct EnvironmentsModelHost<Content: View>: View {
    @Environment(SessionFactory.self) private var sessions
    @State private var presentation = FormPresentation<EnvironmentsModel>(stop: { $0.close() })
    let title: String
    @ViewBuilder let content: (EnvironmentsModel) -> Content

    var body: some View {
        Group {
            if let model = presentation.model {
                content(model)
            } else {
                LoadingIndicator()
            }
        }
        .navigationTitle(title)
        .onAppear { presentation.start(makeModel: sessions.makeEnvironmentsModel) }
        .onDisappear { presentation.close() }
    }
}
