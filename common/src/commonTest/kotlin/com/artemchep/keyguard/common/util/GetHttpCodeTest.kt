package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.util.signalr.HubConnectionHttpException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class GetHttpCodeTest {
    @Test
    fun `reads SignalR status through reconnect wrapper`() {
        val exception = ReconnectFatalException(
            HubConnectionHttpException(
                statusCode = HttpStatusCode.Unauthorized,
                message = "Unauthorized",
            ),
        )

        assertEquals(HttpStatusCode.Unauthorized.value, exception.getHttpCode())
    }
}
