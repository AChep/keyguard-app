package com.artemchep.keyguard.feature.home.vault.screen

import arrow.core.Either
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.quicksearch.createCopyText
import com.artemchep.keyguard.feature.home.vault.quicksearch.createSecret
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.copy_card_number
import com.artemchep.keyguard.res.copy_cvv_code
import com.artemchep.keyguard.res.copy_password
import com.artemchep.keyguard.res.copy_username
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

class VaultRePromptTest {
    @Test
    fun `primary copy of a username is not a secret`() {
        val secret = createSecret(
            login = DSecret.Login(username = "user"),
        ).copy(reprompt = true)

        val copy = vaultViewPrimaryCopy(secret)

        assertEquals(
            VaultViewPrimaryCopy(
                value = "user",
                type = CopyText.Type.USERNAME,
                secret = false,
            ),
            copy,
        )
    }

    @Test
    fun `primary copy of a card number is a secret`() {
        val secret = createSecret(
            type = DSecret.Type.Card,
            card = DSecret.Card(number = "4111111111111111"),
        )

        val copy = vaultViewPrimaryCopy(secret)

        assertEquals(
            VaultViewPrimaryCopy(
                value = "4111111111111111",
                type = CopyText.Type.CARD_NUMBER,
                secret = true,
            ),
            copy,
        )
    }

    @Test
    fun `primary copy of notes is a secret`() {
        val secret = createSecret(
            type = DSecret.Type.SecureNote,
            notes = "note",
        )

        val copy = vaultViewPrimaryCopy(secret)

        assertEquals(
            VaultViewPrimaryCopy(
                value = "note",
                type = CopyText.Type.VALUE,
                secret = true,
            ),
            copy,
        )
    }

    @Test
    fun `primary copy is null for an empty cipher`() {
        val secret = createSecret(
            type = DSecret.Type.SecureNote,
        )

        assertNull(vaultViewPrimaryCopy(secret))
    }

    @Test
    fun `list actions of a login offer to copy the password`() = runTest {
        val secret = createSecret(
            login = DSecret.Login(
                username = "user",
                password = "password",
            ),
        )

        val titles = listActionTitles(secret)

        assertEquals(
            listOf(
                Res.string.copy_username.wrap(),
                Res.string.copy_password.wrap(),
            ),
            titles,
        )
    }

    @Test
    fun `list actions of a re-prompt login do not offer to copy the password`() = runTest {
        val secret = createSecret(
            login = DSecret.Login(
                username = "user",
                password = "password",
            ),
        ).copy(reprompt = true)

        val titles = listActionTitles(secret)

        assertEquals(
            listOf(
                Res.string.copy_username.wrap(),
            ),
            titles,
        )
    }

    @Test
    fun `list actions of a card offer to copy the number and the code`() = runTest {
        val secret = createSecret(
            type = DSecret.Type.Card,
            card = DSecret.Card(
                number = "4111111111111111",
                code = "123",
            ),
        )

        val titles = listActionTitles(secret)

        assertEquals(
            listOf(
                Res.string.copy_card_number.wrap(),
                Res.string.copy_cvv_code.wrap(),
            ),
            titles,
        )
    }

    @Test
    fun `list actions of a re-prompt card do not offer to copy secrets`() = runTest {
        val secret = createSecret(
            type = DSecret.Type.Card,
            card = DSecret.Card(
                number = "4111111111111111",
                code = "123",
            ),
        ).copy(reprompt = true)

        val titles = listActionTitles(secret)

        assertEquals(emptyList(), titles)
    }
}

private suspend fun listActionTitles(
    secret: DSecret,
): List<TextHolder> {
    var actions: List<FlatItemAction> = emptyList()
    secret.toVaultListItem(
        copy = createCopyText(),
        translator = TestTranslatorScope,
        getTotpCode = TestGetTotpCode,
        appIcons = false,
        websiteIcons = false,
        concealFields = false,
        organizationsById = emptyMap(),
        onClick = {
            actions = it
            VaultItem2.Item.Action.None
        },
        onClickAttachment = { null },
        onClickPasskey = { null },
        onClickPassword = { null },
        localStateFlow = MutableStateFlow(
            VaultItem2.Item.LocalState(
                openedState = VaultItem2.Item.OpenedState(isOpened = false),
                selectableItemState = SelectableItemState(
                    selecting = false,
                    selected = false,
                    onClick = null,
                    onLongClick = null,
                ),
            ),
        ),
    )
    return actions.map { it.title }
}

private object TestGetTotpCode : GetTotpCode {
    override fun invoke(
        token: TotpToken,
    ): Flow<Either<Throwable, TotpCode>> = emptyFlow()
}

private object TestTranslatorScope : TranslatorScope {
    override suspend fun translate(res: StringResource): String = res.toString()

    override suspend fun translate(res: StringResource, vararg args: Any): String =
        res.toString()

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = res.toString()
}
