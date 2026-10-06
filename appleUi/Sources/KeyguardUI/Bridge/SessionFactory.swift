import Observation
import KeyguardShared

/// Makes one session or editor model per presentation; holds no presentation state.
/// Observable only so it can live in the environment like the feature models.
@MainActor
@Observable
final class SessionFactory {
    @ObservationIgnored private let core: KeyguardCore

    init(core: KeyguardCore) { self.core = core }

    func makeGeneratorSession() -> GeneratorSession { core.makeGeneratorSession() }

    func makeGeneratorHistorySession() -> ListSession<GeneratorHistorySnapshot> {
        core.makeGeneratorHistorySession()
    }

    func makeGpgToolsModel() -> GpgToolsModel { GpgToolsModel(source: core.makeGpgToolsSession()) }

    func makeAddForm() -> AddFormModel { AddFormModel(core: core) }

    func makeSendListSession() -> SendListSession { core.makeSendListSession() }

    func makeSendDetailSession(target: ItemDetailTarget) -> SendDetailSession {
        core.makeSendDetailSession(itemId: target.itemId, accountId: target.accountId)
    }

    func makeUrlOverrideListSession() -> ListSession<UrlRuleListSnapshot> { core.makeUrlOverrideListSession() }

    func makeChangePasswordSession() -> ChangePasswordSession { core.makeChangePasswordSession() }

    func makeFeedbackSession() -> FeedbackSession { core.makeFeedbackSession() }
}
