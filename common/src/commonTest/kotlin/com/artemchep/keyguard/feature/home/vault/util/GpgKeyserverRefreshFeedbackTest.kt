package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_keyserver_refresh_failed_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_not_found_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_skipped_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_success_title
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgKeyserverRefreshFeedbackTest {
    @Test
    fun `accepted refresh reports success`() {
        val feedback = RefreshGpgPublicKeysResult(1, 0, 0).toFeedback()
        assertEquals(Res.string.gpg_keyserver_refresh_success_title, feedback.title)
        assertEquals(ToastMessage.Type.SUCCESS, feedback.type)
    }

    @Test
    fun `failed refresh is not reported as not found or success`() {
        for (refreshed in listOf(0, 1)) {
            val feedback = RefreshGpgPublicKeysResult(refreshed, 0, 0, 1).toFeedback()
            assertEquals(Res.string.gpg_keyserver_refresh_failed_title, feedback.title)
            assertEquals(ToastMessage.Type.ERROR, feedback.type)
        }
    }

    @Test
    fun `not found and skipped have distinct messages`() {
        val notFound = RefreshGpgPublicKeysResult(0, 1, 0).toFeedback()
        val skipped = RefreshGpgPublicKeysResult(0, 0, 1).toFeedback()
        assertEquals(Res.string.gpg_keyserver_refresh_not_found_title, notFound.title)
        assertEquals(Res.string.gpg_keyserver_refresh_skipped_title, skipped.title)
        assertEquals(ToastMessage.Type.INFO, notFound.type)
        assertEquals(ToastMessage.Type.INFO, skipped.type)
    }
}
