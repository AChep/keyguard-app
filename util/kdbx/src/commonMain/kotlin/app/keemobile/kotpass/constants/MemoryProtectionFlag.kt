package app.keemobile.kotpass.constants

import app.keemobile.kotpass.xml.FormatXml

/**
 * Specifies which sensitive fields of an entry are protected in memory.
 */
enum class MemoryProtectionFlag(val value: String) {
    Title(FormatXml.Tags.Meta.MemoryProtection.ProtectTitle),
    UserName(FormatXml.Tags.Meta.MemoryProtection.ProtectUserName),
    Password(FormatXml.Tags.Meta.MemoryProtection.ProtectPassword),
    Url(FormatXml.Tags.Meta.MemoryProtection.ProtectUrl),
    Notes(FormatXml.Tags.Meta.MemoryProtection.ProtectNotes);

    fun toBasicField() = when (this) {
        Title -> BasicField.Title
        UserName -> BasicField.UserName
        Password -> BasicField.Password
        Url -> BasicField.Url
        Notes -> BasicField.Notes
    }
}
