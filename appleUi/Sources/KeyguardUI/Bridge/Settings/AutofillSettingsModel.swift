import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class AutofillSettingsModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Only live while the AutoFill settings screen is on screen.
    private(set) var autofillSettings: AutofillSettingsSnapshot = AutofillSettingsSnapshot.companion.empty

    @ObservationIgnored private var autofillSettingsSubscription: BridgeObservation?

    func startAutofillSettingsObservation() {
        startObservation(
            \.autofillSettingsSubscription, into: \.autofillSettings, observe: core.observeAutofillSettings)
    }

    func stopAutofillSettingsObservation() {
        stopObservation(
            \.autofillSettingsSubscription, resetting: \.autofillSettings, to: AutofillSettingsSnapshot.companion.empty)
    }

    func setAutofillCopyTotp(_ value: Bool) { core.setAutofillCopyTotp(value: value) }

    func setAutofillSaveRequest(_ value: Bool) { core.setAutofillSaveRequest(value: value) }

    func setAutofillSaveUri(_ value: Bool) { core.setAutofillSaveUri(value: value) }

    func setAutofillDefaultMatchDetection(_ optionId: String) {
        core.setAutofillDefaultMatchDetection(optionId: optionId)
    }
}
