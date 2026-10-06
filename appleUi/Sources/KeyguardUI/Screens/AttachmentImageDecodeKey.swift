import Foundation

/// Repeated Data-to-NSData bridges can allocate different objects for identical
/// bytes. Retain the immutable input and compare contents to keep task IDs stable.
struct AttachmentImageDecodeKey: Equatable {
    let data: NSData?
    let decodeErrorMessage: String?

    static func == (lhs: Self, rhs: Self) -> Bool {
        guard lhs.decodeErrorMessage == rhs.decodeErrorMessage else { return false }
        switch (lhs.data, rhs.data) {
        case (nil, nil): return true
        case let (lhs?, rhs?): return lhs === rhs || lhs.isEqual(to: rhs as Data)
        default: return false
        }
    }
}
