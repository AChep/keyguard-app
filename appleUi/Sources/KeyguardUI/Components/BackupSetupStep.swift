enum BackupSetupStep: Int, CaseIterable, Hashable {
    case destination = 1
    case protection
    case contents
    case review

    var number: Int { rawValue }

    var title: String {
        switch self {
        case .destination: L10n.prefItemAutomaticBackupsWizardDestinationTitle
        case .protection: L10n.prefItemAutomaticBackupsWizardProtectionTitle
        case .contents: L10n.prefItemAutomaticBackupsWizardContentsTitle
        case .review: L10n.prefItemAutomaticBackupsWizardReviewTitle
        }
    }

    var subtitle: String {
        switch self {
        case .destination: L10n.prefItemAutomaticBackupsWizardDestinationDetail
        case .protection: L10n.prefItemAutomaticBackupsWizardProtectionDetail
        case .contents: L10n.prefItemAutomaticBackupsWizardContentsDetail
        case .review: L10n.prefItemAutomaticBackupsWizardReviewDetail
        }
    }

    var systemImage: String {
        switch self {
        case .destination: "externaldrive"
        case .protection: "lock.shield"
        case .contents: "archivebox"
        case .review: "checkmark.shield"
        }
    }
}
