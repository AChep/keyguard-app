package com.artemchep.keyguard.feature.add.attachment

import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentItemUtilTest {
    @Test
    fun `editable attachment state exposes writable name field`() {
        val handle = TextFieldHandle(
            sink = MutableStateFlow(TextCell("invoice.pdf")),
        )
        val name = attachmentNameTextField(
            cell = handle.sink.value,
            editable = true,
            handle = handle,
        )

        assertEquals("invoice.pdf", name.text)
        assertEquals(0, name.textRevision)
        val onChange = requireNotNull(name.onChange)
        assertNotNull(onChange)
        onChange("report.pdf")

        // A user edit moves the text and keeps the revision.
        assertEquals("report.pdf", handle.sink.value.text)
        assertEquals(0, handle.sink.value.revision)

        val state = attachmentState(
            name = attachmentNameTextField(
                cell = handle.sink.value,
                editable = true,
                handle = handle,
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

        // A programmatic write moves the text and bumps the revision.
        val onSetText = requireNotNull(state.name.onSetText)
        onSetText("renamed.pdf")
        assertEquals("renamed.pdf", handle.sink.value.text)
        assertEquals(1, handle.sink.value.revision)
    }

    @Test
    fun `read only attachment state exposes immutable synced field`() {
        val handle = TextFieldHandle(
            sink = MutableStateFlow(TextCell("invoice.pdf", revision = 3)),
        )
        val state = attachmentState(
            name = attachmentNameTextField(
                cell = handle.sink.value,
                editable = false,
                handle = handle,
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
        assertEquals(3, state.name.textRevision)
        assertEquals("12 KB", state.size)
        assertTrue(state.synced)
        assertNull(state.name.onChange)
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
                name = TextFieldModel(
                    text = "renamed.pdf",
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
