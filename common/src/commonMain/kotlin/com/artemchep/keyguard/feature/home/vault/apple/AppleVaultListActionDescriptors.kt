package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction

/** Projects sibling-surface actions using the shared section and id rules. */
suspend fun TranslatorScope.buildSiblingAppleActionDescriptors(
    items: List<ContextItem>,
    surface: String,
    log: (String) -> Unit = { message -> println(message) },
): List<AppleVaultActionDescriptor> = buildAppleActionDescriptors(
    items = items,
    surface = surface,
    log = log,
)

internal suspend fun TranslatorScope.buildAppleActionDescriptors(
    items: List<ContextItem>,
    surface: String,
    log: (String) -> Unit = { message -> println(message) },
): List<AppleVaultActionDescriptor> {
    val out = ArrayList<AppleVaultActionDescriptor>(items.size)
    var pendingSection = false
    for (item in items) {
        when (item) {
            // A leading section draws nothing; only an
            // inter-group break matters.
            is ContextItem.Section -> if (out.isNotEmpty()) pendingSection = true
            is FlatItemAction -> {
                val descriptor = toAppleActionDescriptor(
                    action = item,
                    startsSection = pendingSection,
                    surface = surface,
                    log = log,
                ) ?: continue
                out += descriptor
                pendingSection = false
            }

            is ContextItem.Custom -> Unit
        }
    }
    return out
}

internal suspend fun TranslatorScope.toAppleActionDescriptor(
    action: FlatItemAction,
    startsSection: Boolean = false,
    surface: String,
    log: (String) -> Unit = { message -> println(message) },
): AppleVaultActionDescriptor? {
    val id = action.id
    val title = translate(action.title)
    if (id == null) {
        log(
            "[E]/AppleVaultList: MISSING FlatItemAction.id on '$title' @ $surface â " +
                    "the action can not be dispatched by id and is skipped!",
        )
        return null
    }
    val role = when {
        action.danger -> AppleVaultActionDescriptor.ROLE_DESTRUCTIVE
        else -> id.toAppleToggleRole() ?: AppleVaultActionDescriptor.ROLE_NORMAL
    }
    return AppleVaultActionDescriptor(
        id = id,
        title = title,
        subtitle = action.text?.let { translate(it) }.orEmpty(),
        // The session's symbol table maps ids to SF Symbols.
        symbol = "",
        role = role,
        startsSection = startsSection,
        isCopy = action.type == FlatItemAction.Type.COPY,
    )
}

private fun String.toAppleToggleRole(): Int? = when {
    startsWith("vault.action.") && endsWith(".true") -> AppleVaultActionDescriptor.ROLE_TOGGLE_ON
    startsWith("vault.action.") && endsWith(".false") -> AppleVaultActionDescriptor.ROLE_TOGGLE_OFF
    else -> null
}
