package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut

@Immutable
internal data class QuickSearchState(
    val query: TextFieldModel2 = TextFieldModel2(mutableStateOf("")),
    val results: List<QuickSearchResultItem> = emptyList(),
    val emptyState: QuickSearchEmptyState = QuickSearchEmptyState.Loading,
    val selectedItem: QuickSearchResultItem? = null,
    val selectedItemId: String? = null,
    val defaultAction: QuickSearchActionType? = null,
    val selectedActionIndex: Int? = null,
    val actions: List<QuickSearchAction> = emptyList(),
    val onSelectItem: (String) -> Unit = { },
    val onMoveSelection: (Int) -> Unit = { },
    val onMoveActionSelection: (Int) -> Unit = { },
    val onClearActionSelection: () -> Unit = { },
) {
}

@Immutable
internal data class QuickSearchResultItem(
    val item: VaultItem2.Item,
    val selected: Boolean,
)

@Immutable
internal sealed interface QuickSearchEmptyState {
    @Immutable
    data object Loading : QuickSearchEmptyState

    @Immutable
    data object Idle : QuickSearchEmptyState

    @Immutable
    data object NoItems : QuickSearchEmptyState

    @Immutable
    data class AddAccount(
        val onAddAccount: ((AccountType) -> Unit)? = null,
    ) : QuickSearchEmptyState
}

@Immutable
internal data class QuickSearchAction(
    val type: QuickSearchActionType,
    val title: String,
    val shortcut: KeyShortcut? = null,
    val selected: Boolean = false,
)

@Immutable
internal enum class QuickSearchActionType {
    CopyPrimary,
    CopySecret,
    CopyOtp,
    OpenInBrowser,
}

internal data class QuickSearchCopyValue(
    val value: String,
    val type: CopyText.Type,
)

internal data class QuickSearchContent(
    val results: List<VaultItem2.Item> = emptyList(),
    val emptyState: QuickSearchEmptyState = QuickSearchEmptyState.Idle,
)

internal fun quickSearchContent(
    results: List<VaultItem2.Item>?,
    hasAccounts: Boolean,
    onAddAccount: ((AccountType) -> Unit)? = null,
): QuickSearchContent = when {
    !hasAccounts -> QuickSearchContent(
        emptyState = QuickSearchEmptyState.AddAccount(
            onAddAccount = onAddAccount,
        ),
    )

    results == null -> QuickSearchContent(
        emptyState = QuickSearchEmptyState.Loading,
    )

    results.isEmpty() -> QuickSearchContent(
        emptyState = QuickSearchEmptyState.NoItems,
    )

    else -> QuickSearchContent(
        results = results,
    )
}

internal fun reconcileSelectedResultId(
    currentSelectedItemId: String?,
    results: List<VaultItem2.Item>,
): String? {
    if (results.isEmpty()) {
        return null
    }

    return currentSelectedItemId
        ?.takeIf { selectedItemId ->
            results.any { it.id == selectedItemId }
        }
        ?: results.first().id
}

internal fun moveSelectedResultId(
    currentSelectedItemId: String?,
    results: List<VaultItem2.Item>,
    direction: Int,
): String? {
    if (results.isEmpty()) {
        return null
    }

    val currentIndex = currentSelectedItemId
        ?.let { selectedItemId ->
            results.indexOfFirst { it.id == selectedItemId }
        }
        ?.takeIf { it >= 0 }
        ?: if (direction >= 0) -1 else results.size
    val nextIndex = (currentIndex + direction)
        .coerceIn(0, results.lastIndex)
    return results[nextIndex].id
}

internal fun moveSelectedActionIndex(
    currentSelectedActionIndex: Int?,
    actions: List<QuickSearchActionType>,
    direction: Int,
): Int? {
    if (actions.isEmpty()) {
        return null
    }

    val currentIndex = currentSelectedActionIndex
        ?: if (direction >= 0) -1 else actions.size
    return (currentIndex + direction)
        .coerceIn(0, actions.lastIndex)
}

internal fun quickSearchActionTypes(
    secret: DSecret,
): List<QuickSearchActionType> = buildList {
    if (quickSearchPrimaryCopy(secret) != null) {
        add(QuickSearchActionType.CopyPrimary)
    }
    if (quickSearchSecretCopy(secret) != null) {
        add(QuickSearchActionType.CopySecret)
    }
    if (quickSearchOtpToken(secret) != null) {
        add(QuickSearchActionType.CopyOtp)
    }
    if (quickSearchLaunchUrl(secret) != null) {
        add(QuickSearchActionType.OpenInBrowser)
    }
}

internal fun defaultQuickSearchActionType(
    secret: DSecret,
): QuickSearchActionType? = quickSearchActionTypes(secret)
    .firstOrNull()

internal fun quickSearchPrimaryCopy(
    secret: DSecret,
): QuickSearchCopyValue? = when {
    !secret.login?.username.isNullOrEmpty() -> QuickSearchCopyValue(
        value = secret.login!!.username!!,
        type = CopyText.Type.USERNAME,
    )

    !secret.card?.number.isNullOrEmpty() && !secret.reprompt -> QuickSearchCopyValue(
        value = secret.card!!.number!!,
        type = CopyText.Type.CARD_NUMBER,
    )

    !secret.identity?.email.isNullOrEmpty() -> QuickSearchCopyValue(
        value = secret.identity!!.email!!,
        type = CopyText.Type.EMAIL,
    )

    !secret.identity?.phone.isNullOrEmpty() -> QuickSearchCopyValue(
        value = secret.identity!!.phone!!,
        type = CopyText.Type.PHONE_NUMBER,
    )

    !secret.sshKey?.publicKey.isNullOrEmpty() -> QuickSearchCopyValue(
        value = secret.sshKey!!.publicKey!!,
        type = CopyText.Type.KEY,
    )

    secret.notes.isNotBlank() && !secret.reprompt -> QuickSearchCopyValue(
        value = secret.notes,
        type = CopyText.Type.VALUE,
    )

    else -> null
}

internal fun quickSearchSecretCopy(
    secret: DSecret,
): QuickSearchCopyValue? {
    if (secret.reprompt) {
        return null
    }

    return when {
        !secret.login?.password.isNullOrEmpty() -> QuickSearchCopyValue(
            value = secret.login!!.password!!,
            type = CopyText.Type.PASSWORD,
        )

        !secret.card?.code.isNullOrEmpty() -> QuickSearchCopyValue(
            value = secret.card!!.code!!,
            type = CopyText.Type.CARD_CVV,
        )

        !secret.sshKey?.privateKey.isNullOrEmpty() -> QuickSearchCopyValue(
            value = secret.sshKey!!.privateKey!!,
            type = CopyText.Type.KEY,
        )

        else -> null
    }
}

internal fun quickSearchOtpToken(
    secret: DSecret,
): DSecret.Login.Totp? {
    if (secret.reprompt) {
        return null
    }

    return secret.login?.totp
}

internal fun quickSearchLaunchUrl(
    secret: DSecret,
): String? = secret.uris
    .asSequence()
    .map { it.uri }
    .firstOrNull { uri ->
        uri.startsWith("https://", ignoreCase = true) ||
                uri.startsWith("http://", ignoreCase = true)
    }

internal fun quickSearchShortcut(
    actionType: QuickSearchActionType,
): KeyShortcut? = when (actionType) {
    QuickSearchActionType.CopyPrimary -> KeyShortcut(
        key = Key.C,
        isCtrlPressed = true,
    )

    QuickSearchActionType.CopySecret -> KeyShortcut(
        key = Key.C,
        isCtrlPressed = true,
        isShiftPressed = true,
    )

    QuickSearchActionType.CopyOtp -> KeyShortcut(
        key = Key.C,
        isCtrlPressed = true,
        isAltPressed = true,
    )

    QuickSearchActionType.OpenInBrowser -> KeyShortcut(
        key = Key.F,
        isCtrlPressed = true,
        isShiftPressed = true,
    )
}
