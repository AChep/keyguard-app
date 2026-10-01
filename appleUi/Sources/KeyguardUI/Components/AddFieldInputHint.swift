enum AddFieldInputHint {
    /// Plain text (name, custom text/value, note).
    case plain
    case username
    case password
    case url
    /// Base32 authenticator secret.
    case totp
}
