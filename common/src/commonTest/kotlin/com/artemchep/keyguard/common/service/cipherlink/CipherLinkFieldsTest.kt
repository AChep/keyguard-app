package com.artemchep.keyguard.common.service.cipherlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CipherLinkFieldsTest {
    @Test
    fun `formats a one-based field name`() {
        assertEquals("keyguard.link.1", CipherLinkFields.fieldName(1))
        assertEquals("keyguard.link.12", CipherLinkFields.fieldName(12))
    }

    @Test
    fun `formats the links into contiguous one-based fields`() {
        val fields = CipherLinkFields.format(
            listOf(TARGET_REMOTE_ID, OTHER_REMOTE_ID),
        )

        assertEquals(
            listOf(
                "keyguard.link.1" to "keyguard://cipher/$TARGET_REMOTE_ID",
                "keyguard.link.2" to "keyguard://cipher/$OTHER_REMOTE_ID",
            ),
            fields,
        )
    }

    @Test
    fun `formats without leaving a gap for a link that is not canonical`() {
        val fields = CipherLinkFields.format(
            listOf("not-a-uuid", TARGET_REMOTE_ID),
        )

        assertEquals(
            listOf("keyguard.link.1" to "keyguard://cipher/$TARGET_REMOTE_ID"),
            fields,
        )
    }

    @Test
    fun `formats the uppercase link into its canonical form`() {
        val fields = CipherLinkFields.format(
            listOf(
                TARGET_REMOTE_ID.uppercase(),
                TARGET_REMOTE_ID,
                OTHER_REMOTE_ID,
                TARGET_REMOTE_ID,
            ),
        )

        assertEquals(
            listOf(
                "keyguard.link.1" to "keyguard://cipher/$TARGET_REMOTE_ID",
                "keyguard.link.2" to "keyguard://cipher/$OTHER_REMOTE_ID",
            ),
            fields,
        )
    }

    @Test
    fun `parses the index of a reserved field name`() {
        assertEquals(1, CipherLinkFields.parseFieldIndex("keyguard.link.1"))
        assertEquals(12, CipherLinkFields.parseFieldIndex("keyguard.link.12"))
    }

    @Test
    fun `rejects the names that are not a reserved link field`() {
        assertNull(CipherLinkFields.parseFieldIndex(null))
        assertNull(CipherLinkFields.parseFieldIndex(""))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link."))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link.0"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link.-1"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link.+1"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link.1x"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.link.1 "))
        assertNull(CipherLinkFields.parseFieldIndex(" keyguard.link.1"))
        assertNull(CipherLinkFields.parseFieldIndex("Keyguard.Link.1"))
        assertNull(CipherLinkFields.parseFieldIndex("keyguard.gpg.fingerprint"))
    }

    @Test
    fun `parses a field into its ordinal and canonical link`() {
        val field = assertNotNull(
            CipherLinkFields.parse(
                name = CipherLinkFields.fieldName(12),
                value = "keyguard://cipher/${TARGET_REMOTE_ID.uppercase()}",
            ),
        )

        assertEquals(12, field.index)
        assertEquals(TARGET_REMOTE_ID, field.link.remoteCipherId)
    }

    @Test
    fun `rejects an invalid field name or value`() {
        assertNull(
            CipherLinkFields.parse(
                name = "Related",
                value = "keyguard://cipher/$TARGET_REMOTE_ID",
            ),
        )
        assertNull(
            CipherLinkFields.parse(
                name = CipherLinkFields.fieldName(1),
                value = "not-a-link",
            ),
        )
    }

    @Test
    fun `decodes parsed fields by ordinal and collapses duplicate targets`() {
        val fields = listOf(
            assertNotNull(
                CipherLinkFields.parse(
                    name = CipherLinkFields.fieldName(3),
                    value = "keyguard://cipher/$OTHER_REMOTE_ID",
                ),
            ),
            assertNotNull(
                CipherLinkFields.parse(
                    name = CipherLinkFields.fieldName(2),
                    value = "keyguard://cipher/${TARGET_REMOTE_ID.uppercase()}",
                ),
            ),
            assertNotNull(
                CipherLinkFields.parse(
                    name = CipherLinkFields.fieldName(1),
                    value = "keyguard://cipher/$TARGET_REMOTE_ID",
                ),
            ),
        )

        assertEquals(
            listOf(TARGET_REMOTE_ID, OTHER_REMOTE_ID),
            CipherLinkFields.decode(fields).map(CipherLink::remoteCipherId),
        )
    }

    @Test
    fun `canonicalizes ids without exposing the link wrapper`() {
        assertEquals(
            listOf(TARGET_REMOTE_ID, OTHER_REMOTE_ID),
            canonicalizeCipherLinkIds(
                listOf(
                    TARGET_REMOTE_ID.uppercase(),
                    TARGET_REMOTE_ID,
                    OTHER_REMOTE_ID,
                ),
            ),
        )
    }
}

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
