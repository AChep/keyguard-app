package com.artemchep.keyguard.apple.model

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultItemReferenceTest {
    @Test
    fun referenceCarriesTheItemAndFieldAndOpensTheItem() = runTest {
        var opened = false
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshot = buildVaultItemSnapshots(
            items = listOf(
                VaultViewItem.Value(
                    id = "environment.0",
                    title = "DB_PASSWORD",
                    value = "secret",
                    reference = VaultViewItem.Value.Reference(
                        item = presentation("Database"),
                        field = "Password",
                        onClick = { opened = true },
                    ),
                ),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = handlers,
        ).single()
        val reference = assertNotNull(snapshot.reference)
        assertEquals("Database", reference.title)
        assertEquals("Password", reference.text)
        assertFalse(reference.unavailable)
        handlers.getValue(assertNotNull(reference.clickActionId)).invoke()
        assertTrue(opened)
    }

    @Test
    fun withheldReferenceSendsNoValueAndAMissingItemCanNotBeOpened() = runTest {
        val snapshot = buildVaultItemSnapshots(
            items = listOf(
                VaultViewItem.Value(
                    id = "environment.0",
                    title = "DB_PASSWORD",
                    value = "",
                    visibility = Visibility(
                        concealed = true,
                        hidden = true,
                    ),
                    reference = VaultViewItem.Value.Reference(
                        item = null,
                        field = "Password",
                    ),
                ),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = mutableMapOf(),
            revealedIds = emptySet(),
            onRequestReveal = { "reveal" },
        ).single()
        assertTrue(snapshot.revealLocked)
        assertNull(snapshot.text)
        val reference = assertNotNull(snapshot.reference)
        assertEquals("Password", reference.title)
        assertNull(reference.text)
        assertTrue(reference.unavailable)
        assertNull(reference.clickActionId)
    }

    @Test
    fun itemWithoutNameIsShownByItsFieldAlone() = runTest {
        val snapshot = buildVaultItemSnapshots(
            items = listOf(
                VaultViewItem.Value(
                    id = "environment.0",
                    title = "DB_PASSWORD",
                    value = "secret",
                    reference = VaultViewItem.Value.Reference(
                        item = presentation(" "),
                        field = "Password",
                    ),
                ),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = mutableMapOf(),
        ).single()
        val reference = assertNotNull(snapshot.reference)
        assertEquals("Password", reference.title)
        assertNull(reference.text)
        assertFalse(reference.unavailable)
    }

    // The snapshot reads only the title of the referenced item.
    private fun presentation(title: String) = object : VaultItemPresentation {
        override val source: DSecret get() = error("Not used by the snapshot")
        override val icon: VaultItemIcon get() = error("Not used by the snapshot")
        override val title = AnnotatedString(title)
        override val text: String? = null
    }
}
