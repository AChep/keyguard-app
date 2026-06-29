package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebDavAuthorizationMappingTest {
    @Test
    fun `creates basic authorization from username and password`() {
        assertEquals(
            WebDavAuthorization.Basic(
                username = "alice",
                password = "secret",
            ),
            webDavAuthorizationOf(
                username = " alice ",
                password = Password("secret"),
            ),
        )
    }

    @Test
    fun `omits authorization without username`() {
        assertNull(
            webDavAuthorizationOf(
                username = " ",
                password = Password("secret"),
            ),
        )
    }

    @Test
    fun `uses empty password when password is absent`() {
        assertEquals(
            WebDavAuthorization.Basic(
                username = "alice",
                password = "",
            ),
            webDavAuthorizationOf(
                username = "alice",
                password = null,
            ),
        )
    }
}
