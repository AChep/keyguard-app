package com.artemchep.keyguard.apple.lists

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon
import com.artemchep.keyguard.feature.agent.history.AgentHistoryOutcome
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHistoryDetailsSnapshotTest {
    @Test
    fun mapsEveryRowKindAndTheOutcome() {
        val details = AgentHistoryDetails(
            id = "id",
            icon = AgentHistoryDetailsIcon.RUN,
            title = "Run a command",
            outcome = AgentHistoryOutcome.FAILURE,
            outcomeText = "Denied",
            formattedDate = "12:00",
            sections = listOf(
                AgentHistoryDetails.Section(
                    id = "command",
                    title = "Command",
                    rows = listOf(
                        AgentHistoryDetails.Row.Code(
                            id = "command.line",
                            icon = AgentHistoryDetailsIcon.RUN,
                            label = "Command",
                            value = "npm start",
                        ),
                        AgentHistoryDetails.Row.Text(
                            id = "command.path",
                            icon = null,
                            label = "Working directory",
                            value = "/project",
                            monospace = true,
                        ),
                    ),
                ),
            ),
        )

        val snapshot = details.toSnapshot()
        assertEquals("RUN", snapshot.icon)
        assertEquals("FAILURE", snapshot.outcome)
        assertEquals("Denied", snapshot.outcomeText)
        val rows = snapshot.sections.single().rows
        assertEquals(AgentHistoryDetailsRowKind.CODE, rows[0].kind)
        assertEquals("npm start", rows[0].value)
        assertEquals(AgentHistoryDetailsRowKind.TEXT, rows[1].kind)
        assertEquals("", rows[1].icon)
        assertTrue(rows[1].monospace)
    }

    @Test
    fun aReferenceOpensTheItemOnlyWhileItExists() {
        val available = reference(item = presentation("Work key"), accountId = "account")
        val availableReference = assertNotNull(available.reference)
        assertEquals("Work key", availableReference.title)
        assertEquals("SSH Key", availableReference.text)
        assertEquals("keyguard://cipher?itemId=key&accountId=account", availableReference.clickActionId)

        val deleted = reference(item = null, accountId = null)
        val deletedReference = assertNotNull(deleted.reference)
        assertTrue(deletedReference.unavailable)
        assertEquals("SSH Key", deletedReference.title)
        assertNull(deletedReference.text)
        assertNull(deletedReference.clickActionId)
    }

    private fun reference(
        item: VaultItemPresentation?,
        accountId: String?,
    ) = AgentHistoryDetails(
        id = "id",
        icon = AgentHistoryDetailsIcon.SSH_SIGN,
        title = "Sign",
        outcome = AgentHistoryOutcome.SUCCESS,
        outcomeText = "Success",
        formattedDate = "12:00",
        sections = listOf(
            AgentHistoryDetails.Section(
                id = "key",
                title = "Key",
                rows = listOf(
                    AgentHistoryDetails.Row.Reference(
                        id = "reference",
                        label = "SSH Key",
                        cipherId = "key",
                        accountId = accountId,
                        item = item,
                    ),
                ),
            ),
        ),
    ).toSnapshot().sections.single().rows.single()

    private fun presentation(name: String) = object : VaultItemPresentation {
        override val source: DSecret get() = error("Not used")
        override val icon: VaultItemIcon get() = error("Not used")
        override val title = AnnotatedString(name)
        override val text: String? = null
    }
}
