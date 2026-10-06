import SwiftUI

/// Shares observation and reveal-state ownership between inline and stacked details.
struct DetailSessionContent<Target: Equatable, Snapshot, Session, Content: View>: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isObserving = false
    @State private var placeholderIdentity = UUID()
    let model: DetailSessionModel<Target, Snapshot, Session>
    let target: Target
    var initialSnapshot: Snapshot? = nil
    /// Dismisses the hosting sheet once the session completes (a form that saved or popped itself).
    var dismissOnComplete = false
    @ViewBuilder var content: (Snapshot, SessionActions<Session>) -> Content

    var body: some View {
        ZStack {
            if model.target == target, let detail = model.detail, let identity = model.identity {
                content(detail, model.actions(owner: identity))
                    .id(identity)
            } else if let initialSnapshot {
                // Keep sheet chrome and Cancel available before the producer emits.
                content(initialSnapshot, .none)
                    .id(placeholderIdentity)
            } else {
                LoadingIndicator()
            }
        }
        .onChange(of: target) { _, target in
            if isObserving { model.setTarget(target) }
        }
        .onChange(of: model.didComplete) { _, completed in
            if completed && dismissOnComplete { dismiss() }
        }
        .observing(
            start: {
                isObserving = true
                model.setTarget(target)
            },
            stop: {
                isObserving = false
                model.stop()
            }
        )
    }
}

extension DetailSessionContent where Target == Bool {
    init(
        model: DetailSessionModel<Bool, Snapshot, Session>,
        initialSnapshot: Snapshot? = nil,
        dismissOnComplete: Bool = false,
        @ViewBuilder content: @escaping (Snapshot, SessionActions<Session>) -> Content
    ) {
        self.init(
            model: model, target: true, initialSnapshot: initialSnapshot, dismissOnComplete: dismissOnComplete,
            content: content)
    }
}
