import SwiftUI
import KeyguardShared

struct UrlRuleListScreen: View {
    @State private var model: DetailSessionModel<Bool, UrlRuleListSnapshot, ListSession<UrlRuleListSnapshot>>
    let title: String
    let emptyText: String
    let subtitleLabel: String
    let detailLabel: String

    init(
        title: String, emptyText: String, subtitleLabel: String, detailLabel: String,
        makeSession: @escaping () -> ListSession<UrlRuleListSnapshot>
    ) {
        self.title = title
        self.emptyText = emptyText
        self.subtitleLabel = subtitleLabel
        self.detailLabel = detailLabel
        _model = State(
            wrappedValue: DetailSessionModel(
                makeSession: makeSession,
                subscribe: { BridgeObservation($0.observe(onChange: $1)) }
            ))
    }

    var body: some View {
        DetailSessionContent(model: model) { snapshot, perform in
            UrlRuleListView(
                title: title, emptyText: emptyText, subtitleLabel: subtitleLabel, detailLabel: detailLabel,
                snapshot: snapshot,
                onNew: { perform { $0.invokePrimaryAction() } },
                invokeItemAction: { id in perform { $0.invokeItemAction(id: id) } },
                invokeSelectionAction: { id in perform { $0.invokeSelectionAction(id: id) }
                },
                toggleSelection: { id in perform { $0.toggleSelection(itemId: id) } },
                clearSelection: { perform { $0.clearSelection() } }
            )
        }
        .navigationTitle(title)
    }
}
