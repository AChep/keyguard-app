import Foundation

/// Root-owned app dialog routes. Priority intentionally follows the old modifier
/// order so simultaneous shared-producer emissions become deterministic without
/// changing which pending app dialog wins first.
enum RootAppSheetRoute: String, Identifiable, Equatable, CaseIterable {
    case passwordMemory
    case largeType
    case barcode
    case passkeyCredential
    case attachmentPreview
    case confirmation
    case elevatedAccess
    case serviceInfo
    case emailLeak
    case passwordLeak
    case websiteLeak
    case colorPicker
    case infoDialog
    case accountPicker

    var id: String { rawValue }

    @MainActor
    static func preferred(in model: DialogsModel, isAddFormActive: Bool) -> Self? {
        let availability: [(Self, Bool)] = [
            (.passwordMemory, model.passwordMemory != nil),
            (.largeType, model.largeType != nil),
            (.barcode, model.barcode != nil),
            (.passkeyCredential, model.passkeyCredential != nil),
            (.attachmentPreview, model.attachmentPreview != nil),
            (.confirmation, model.confirmation != nil),
            (.elevatedAccess, model.elevatedAccess != nil),
            (.serviceInfo, model.serviceInfo != nil),
            (.emailLeak, model.emailLeak != nil),
            (.passwordLeak, model.passwordLeak != nil),
            (.websiteLeak, model.websiteLeak != nil),
            (.colorPicker, model.colorPicker != nil),
            (.infoDialog, model.infoDialog != nil),
            (.accountPicker, model.accountPicker != nil),
        ]
        return preferred(
            available: Set(availability.compactMap { $0.1 ? $0.0 : nil }),
            isAddFormActive: isAddFormActive,
            hasLocalAuthenticationHost: model.elevatedAccessLocalHost != nil
        )
    }

    static func preferred(
        available: Set<Self>,
        isAddFormActive: Bool,
        hasLocalAuthenticationHost: Bool
    ) -> Self? {
        allCases.first { route in
            guard available.contains(route) else { return false }
            switch route {
            case .confirmation, .accountPicker: return !isAddFormActive
            case .elevatedAccess: return !hasLocalAuthenticationHost
            default: return true
            }
        }
    }

    @MainActor
    func dismiss(in model: DialogsModel) {
        switch self {
        case .passwordMemory:
            model.closePasswordMemory()
        case .largeType:
            model.closeLargeType()
        case .barcode:
            model.closeBarcode()
        case .passkeyCredential:
            model.closePasskeyCredential()
        case .attachmentPreview:
            model.closeAttachmentPreview()
        case .confirmation:
            model.closeConfirmation()
        case .elevatedAccess:
            model.closeElevatedAccess()
        case .serviceInfo:
            model.closeServiceInfo()
        case .emailLeak:
            model.closeEmailLeak()
        case .passwordLeak:
            model.closePasswordLeak()
        case .websiteLeak:
            model.closeWebsiteLeak()
        case .colorPicker:
            model.closeColorPicker()
        case .infoDialog:
            model.closeInfoDialog()
        case .accountPicker:
            model.closeAccountPicker()
        }
    }
}
