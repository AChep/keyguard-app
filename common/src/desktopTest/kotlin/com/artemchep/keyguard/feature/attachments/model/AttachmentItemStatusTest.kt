package com.artemchep.keyguard.feature.attachments.model

import arrow.core.right
import com.artemchep.keyguard.common.service.download.DownloadProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttachmentItemStatusTest {
    @Test
    fun `complete progress with uri maps to downloaded status`() {
        val status = AttachmentItem.Status.of(
            DownloadProgress.Complete("file:///tmp/attachment.bin".right()),
        )

        val downloaded = assertIs<AttachmentItem.Status.Downloaded>(status)
        assertEquals("file:///tmp/attachment.bin", downloaded.localUrl)
    }

    @Test
    fun `complete progress without uri maps to none`() {
        val status = AttachmentItem.Status.of(
            DownloadProgress.Complete(null.right()),
        )

        assertIs<AttachmentItem.Status.None>(status)
    }
}
