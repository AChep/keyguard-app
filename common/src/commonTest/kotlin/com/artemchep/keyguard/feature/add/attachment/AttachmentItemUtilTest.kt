package com.artemchep.keyguard.feature.add.attachment

import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentItemUtilTest {
    @Test
    fun `editable attachment state exposes writable name field`() {
        val backingState = mutableStateOf("invoice.pdf")
        val name = attachmentNameTextField(
            name = "invoice.pdf",
            editable = true,
            state = backingState,
        )

        assertEquals("invoice.pdf", name.text)
        val onChange = requireNotNull(name.onChange)
        assertNotNull(onChange)
        onChange("report.pdf")

        assertEquals("report.pdf", backingState.value)

        val state = attachmentState(
            name = attachmentNameTextField(
                name = backingState.value,
                editable = true,
                state = backingState,
            ),
            config = AttachmentItemStateConfig(
                id = "local-1",
                size = "2 KB",
                synced = false,
            ),
        )

        assertEquals("local-1", state.id)
        assertEquals("report.pdf", state.name.text)
        assertEquals("2 KB", state.size)
        assertFalse(state.synced)
    }

    @Test
    fun `read only attachment state exposes immutable synced field`() {
        val backingState = mutableStateOf("ignored.pdf")
        val state = attachmentState(
            name = attachmentNameTextField(
                name = "invoice.pdf",
                editable = false,
                state = backingState,
            ),
            config = AttachmentItemStateConfig(
                id = "remote-1",
                size = "12 KB",
                synced = true,
                editable = false,
            ),
        )

        assertEquals("remote-1", state.id)
        assertEquals("invoice.pdf", state.name.text)
        assertEquals("12 KB", state.size)
        assertTrue(state.synced)
        assertNull(state.name.onChange)
        assertFalse(state.name.state === backingState)
    }

    @Test
    fun `attachment populator keeps original identity and edited name`() {
        val populator = attachmentStatePopulator<Request, Identity>(
            identity = Identity(
                id = "attachment-1",
                marker = "local",
            ),
        ) { identity, name ->
            copy(
                id = identity.id,
                marker = identity.marker,
                name = name,
            )
        }

        val output = Request().populator(
            AddStateItem.Attachment.State(
                id = "ui-id",
                name = TextFieldModel2(
                    state = mutableStateOf("renamed.pdf"),
                    onChange = null,
                ),
                synced = false,
            ),
        )

        assertEquals("attachment-1", output.id)
        assertEquals("local", output.marker)
        assertEquals("renamed.pdf", output.name)
    }

    private data class Identity(
        val id: String,
        val marker: String,
    )

    private data class Request(
        val id: String? = null,
        val marker: String? = null,
        val name: String? = null,
    )
}
