enum AddFieldInputHint {
    /// Plain text (name, custom text/value, note) — system defaults.
    case plain
    /// Account username — no capitalization/autocorrect, `.username` content type.
    case username
    /// Secret password — no capitalization/autocorrect, `.password` content type.
    case password
    /// Website URI — URL keyboard, no capitalization/autocorrect, `.URL` content type.
    case url
    /// Base32 authenticator secret — no capitalization/autocorrect, no content type.
    case totp
}
