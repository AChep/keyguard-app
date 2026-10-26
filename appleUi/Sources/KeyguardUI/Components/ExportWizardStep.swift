enum ExportWizardStep: Int, CaseIterable, Hashable {
    case format = 1
    case items
    case password
    case review

    var number: Int { rawValue }

    var previous: ExportWizardStep? { ExportWizardStep(rawValue: rawValue - 1) }

    var next: ExportWizardStep? { ExportWizardStep(rawValue: rawValue + 1) }

    var title: String {
        switch self {
        case .format: L10n.exportaccountWizardFormatTitle
        case .items: L10n.exportaccountWizardItemsTitle
        case .password: L10n.exportaccountWizardPasswordTitle
        case .review: L10n.exportaccountWizardReviewTitle
        }
    }

    var subtitle: String {
        switch self {
        case .format: L10n.exportaccountWizardFormatDetail
        case .items: L10n.exportaccountWizardItemsDetail
        case .password: L10n.exportaccountWizardPasswordDetail
        case .review: L10n.exportaccountWizardReviewDetail
        }
    }

    var systemImage: String {
        switch self {
        case .format: "doc.zipper"
        case .items: "list.bullet.rectangle"
        case .password: "lock.shield"
        case .review: "checkmark.shield"
        }
    }
}
