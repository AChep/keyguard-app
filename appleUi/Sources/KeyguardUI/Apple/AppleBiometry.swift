import Foundation
import LocalAuthentication

/// Shared by the app and AutoFill; capability checks populate the device's biometry type.
enum AppleBiometry {
    case faceID
    case touchID
    case other

    static var current: AppleBiometry {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return .faceID
        case .touchID: return .touchID
        default: return .other
        }
    }

    var symbol: String {
        switch self {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .other: return "person.badge.key"
        }
    }

    func unlockTitle(bundle: Bundle) -> String {
        let key: String
        switch self {
        case .faceID: key = "unlock_biometric_face_id_title"
        case .touchID: key = "unlock_biometric_touch_id_title"
        case .other: key = "unlock_biometric_title"
        }
        return NSLocalizedString(key, bundle: bundle, comment: "")
    }

    func confirmTitle(bundle: Bundle) -> String {
        let key: String
        switch self {
        case .faceID: key = "confirm_biometric_face_id_title"
        case .touchID: key = "confirm_biometric_touch_id_title"
        case .other: key = "confirm_biometric_title"
        }
        return NSLocalizedString(key, bundle: bundle, comment: "")
    }
}
