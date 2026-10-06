import SwiftUI

/// Lives above the columns so completed results reconcile even while the list
/// is hidden, loading, or replaced by the accountless empty state.
struct VaultBrowseSelectionWatcher: View {
    @Environment(NavigationModel.self) private var navigationModel
    let model: VaultListSessionModel
    let context: NavigationListContext

    var body: some View {
        // Resolved here, not in the list, so only this view observes row content.
        let rowId = context.detail?.selectedRowId(in: model.store)
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .onAppear { model.setNavigationOrigin(context.origin) }
            .onChange(of: rowId, initial: true) { _, rowId in model.browseSelectedRowId = rowId }
            .clearsMissingBrowseSelection(input(rowId: rowId)) { navigationModel.clearListDetail(context) }
    }

    private func input(rowId: String?) -> BrowseSelectionInput {
        guard let detail = context.detail, detail.cipherId != nil else { return BrowseSelectionInput() }
        return BrowseSelectionInput(
            detailId: detail.instanceId, fromList: detail.fromList, isPresent: rowId != nil,
            isLoaded: model.isLoaded && model.store.structure.revision > 0)
    }
}
