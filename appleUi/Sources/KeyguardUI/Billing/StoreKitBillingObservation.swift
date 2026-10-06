import KeyguardShared

final class StoreKitBillingObservation: AppleBillingObservation {
    private let onCancel: () -> Void
    init(onCancel: @escaping () -> Void) { self.onCancel = onCancel }
    func cancel() { onCancel() }
}
