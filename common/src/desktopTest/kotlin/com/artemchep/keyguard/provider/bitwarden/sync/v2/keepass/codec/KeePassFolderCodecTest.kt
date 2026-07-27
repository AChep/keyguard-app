package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class KeePassFolderCodecTest {
    private val codec = KeePassFolderCodec()

    @Test
    fun `encode uses the local folder id for a new group uuid`() {
        val folderId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"

        val encoded = codec.encodeNew(
            local = testBitwardenFolder(folderId = folderId),
        )

        assertEquals(folderId, encoded.uuid.toString())
    }
}
