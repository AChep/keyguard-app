package com.artemchep.keyguard.apple.vault

internal object VaultActionSymbols {
    private val symbols: Map<String, String> = mapOf(
        // Toolbar overflow (vaultListToolbarFlow).
        "vaultList.archive" to "archivebox",
        "vaultList.trash" to "trash",
        "vaultList.downloads" to "arrow.down.circle",
        "cipherFilters.action" to "line.3.horizontal.decrease.circle",
        "vault.action.always_show_keyboard.true" to "keyboard",
        "vault.action.always_show_keyboard.false" to "keyboard",
        "vault.action.remember_sorting.true" to "arrow.up.arrow.down",
        "vault.action.remember_sorting.false" to "arrow.up.arrow.down",
        "vaultList.sync" to "arrow.triangle.2.circlepath",
        "vaultList.lock" to "lock",
        "vaultList.renameFolder" to "pencil",

        // Per-row copy actions (buildVaultItemCopyActions).
        "vaultList.item.copyUsername" to "person",
        "vaultList.item.copyPassword" to "key",
        "vaultList.item.copyOtp" to "clock",
        "vaultList.item.copyCardNumber" to "creditcard",
        "vaultList.item.copyCvv" to "creditcard.and.123",
        "vaultList.item.copyPhone" to "phone",
        "vaultList.item.copyEmail" to "envelope",
        "vaultList.item.copyPassportNumber" to "person.text.rectangle",
        "vaultList.item.copyLicenseNumber" to "doc.text",

        // Per-row mode menus (buildVaultItemModeMenu; Pick / Save modes).
        "vaultList.pick.autofill" to "wand.and.stars",
        "vaultList.pick.autofillAndSave" to "wand.and.stars.inverse",
        "vaultList.pick.viewDetails" to "info.circle",
        "vaultList.save.viewDetails" to "info.circle",
        "vaultList.save.saveTo" to "square.and.arrow.down",
        "vaultList.savePasskey.saveTo" to "square.and.arrow.down",
        "vaultList.savePassword.saveTo" to "square.and.arrow.down",

        // Bulk selection (createCipherSelectionFlow + cipher*Action helpers).
        "duplicates.selection.addToFavorites" to "star",
        "duplicates.selection.removeFromFavorites" to "star.slash",
        "cipher.enableConfirmAccess" to "lock.shield",
        "cipher.disableConfirmAccess" to "lock.open",
        "cipher.edit" to "pencil",
        "cipher.viewPasswordHistory" to "clock.arrow.circlepath",
        "cipher.viewSshAgentHistory" to "terminal",
        "cipher.changeName" to "character.cursor.ibeam",
        "cipher.changeTags" to "tag",
        "cipher.changePassword" to "key",
        "cipher.mergeInto" to "arrow.triangle.merge",
        "cipher.send" to "paperplane",
        "cipher.copyTo" to "doc.on.doc",
        "cipher.moveToFolder" to "folder",
        "cipher.watchtowerAlerts" to "checkmark.shield",
        "cipher.export" to "square.and.arrow.up",
        "cipher.archive" to "archivebox",
        "cipher.unarchive" to "tray.and.arrow.up",
        "cipher.trash" to "trash",
        "cipher.restore" to "trash.slash",
        "cipher.delete" to "xmark.bin",

        // Create menu (buildPrimaryActions; ids are
        // "vaultList.create." + DSecret.Type.name).
        "vaultList.create.Login" to "person.badge.key",
        "vaultList.create.Card" to "creditcard",
        "vaultList.create.Identity" to "person.text.rectangle",
        "vaultList.create.SecureNote" to "note.text",
        "vaultList.create.SshKey" to "terminal",
        "vaultList.create.GpgKey" to "lock.shield",
        "vaultList.subscriptions" to "star.circle",
    )

    fun symbolFor(actionId: String): String {
        val symbol = symbols[actionId]
        if (symbol == null) {
            println(
                "[Keyguard][action] no SF Symbol for action id='$actionId' — " +
                        "add it to VaultActionSymbols",
            )
            return ""
        }
        return symbol
    }
}
