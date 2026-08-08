package com.artemchep.keyguard.ipctestclient.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope

/**
 * Manual driver for both Keyguard IPC protocols.
 *
 * Everything it does goes through the same `ipc` package the instrumentation
 * suite uses, so a request reproduced by hand here is the same request the suite
 * sends.
 */
class DriverActivity : ComponentActivity() {
    private lateinit var controller: DriverController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registered before the activity starts, as the result API requires.
        val approvals = ActivityApprovalHost(this)
        controller = DriverController(
            context = this,
            approvals = approvals,
            scope = lifecycleScope,
        )
        setContent {
            MaterialTheme {
                DriverScreen(controller)
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
