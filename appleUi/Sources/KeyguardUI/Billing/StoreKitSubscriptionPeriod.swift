import StoreKit

extension Product.SubscriptionPeriod {
    func iso8601(multiplier: Int = 1) -> String? {
        let count = value * multiplier
        switch unit {
        case .day: return "P\(count)D"
        case .week: return "P\(count)W"
        case .month: return "P\(count)M"
        case .year: return "P\(count)Y"
        @unknown default: return nil
        }
    }
}
