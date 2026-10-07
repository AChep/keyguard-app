import Foundation
import KeyguardShared

/// Maps an `S3FormError` name from the Kotlin bridge to its message.
func s3ErrorMessage(_ kind: String?) -> String? {
    switch kind {
    case "EndpointInvalid":
        return L10n.errorS3EndpointInvalid
    case "BucketRequired":
        return L10n.errorS3BucketRequired
    case "BucketInvalid":
        return L10n.errorS3BucketInvalid
    case "PrefixInvalid":
        return L10n.errorS3PrefixInvalid
    case "KeyRequired":
        return L10n.errorS3KeyRequired
    case "KeyInvalid":
        return L10n.errorS3KeyInvalid
    case "KeyExtensionRequired":
        return L10n.errorS3KeyExtension(KeyguardConstants.shared.KEEPASS_DATABASE_EXTENSION)
    case "AccessKeyIdRequired":
        return L10n.errorS3AccessKeyIdRequired
    case "AccessKeyIdInvalid":
        return L10n.errorS3AccessKeyIdInvalid
    case "SecretAccessKeyRequired":
        return L10n.errorS3SecretAccessKeyRequired
    default:
        return nil
    }
}
