package com.artemchep.keyguard.feature.fido2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.util.fido2.FIDO2_RP_ID
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import com.yubico.yubikit.fido.android.ui.FidoClient
import com.yubico.yubikit.fido.android.ui.Origin
import com.yubico.yubikit.fido.android.ui.WebAuthnClientException
import com.yubico.yubikit.fido.client.extensions.HmacSecretExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Lifecycle owner for the SDK's Activity Result launcher, created before onStart. */
class Fido2Activity : ComponentActivity() {
    @Suppress(
        "TooGenericExceptionCaught",
        "SwallowedException",
    ) // Return sanitized errors across the activity boundary.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val client = FidoClient(this, listOf(HmacSecretExtension()))
        if (savedInstanceState != null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val operation = intent.operation()
                val origin = Origin("https://$FIDO2_RP_ID")
                val request = operation.webAuthnRequest()
                val response =
                    when (operation) {
                        is Fido2Operation.Register ->
                            client.makeCredential(origin, request, null, "Keyguard")
                        is Fido2Operation.Derive ->
                            client.getAssertion(origin, request, null, "Keyguard")
                    }.getOrThrow()
                val bytes = operation.webAuthnResponse(response)
                setResult(Activity.RESULT_OK, Intent().putExtra("result", bytes))
            } catch (error: CancellationException) {
                setResult(Activity.RESULT_CANCELED)
            } catch (error: Exception) {
                val code =
                    when {
                        error is Fido2Exception -> error.failure.code
                        error is WebAuthnClientException &&
                            error.webAuthnError == "NotSupportedError" ->
                            Fido2Failure.UNSUPPORTED.code
                        else -> Fido2Failure.REJECTED.code
                    }
                setResult(Activity.RESULT_OK, Intent().putExtra("error", code))
            } finally {
                finish()
            }
        }
    }
}

internal class Fido2ActivityContract : ActivityResultContract<Fido2Operation, Result<ByteArray>>() {
    override fun createIntent(context: Context, input: Fido2Operation) =
        Intent(context, Fido2Activity::class.java).apply {
            putExtra("challenge", input.challenge)
            when (input) {
                is Fido2Operation.Register -> putExtra("user", input.userId)
                is Fido2Operation.Derive -> {
                    putExtra("credential", input.credentialId)
                    putExtra("salt", input.salt)
                }
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Result<ByteArray> = runCatching {
        if (resultCode != Activity.RESULT_OK) throw Fido2Exception(Fido2Failure.CANCELED)
        val code = intent?.getIntExtra("error", 0) ?: Fido2Failure.PROTOCOL.code
        if (code != 0)
            throw Fido2Exception(
                Fido2Failure.entries.firstOrNull { it.code == code } ?: Fido2Failure.PROTOCOL
            )
        intent?.getByteArrayExtra("result") ?: throw Fido2Exception(Fido2Failure.PROTOCOL)
    }
}

private fun Intent.operation(): Fido2Operation {
    val challenge = requireNotNull(getByteArrayExtra("challenge"))
    val user = getByteArrayExtra("user")
    return if (user != null) Fido2Operation.Register(user, challenge)
    else
        Fido2Operation.Derive(
            requireNotNull(getByteArrayExtra("credential")),
            requireNotNull(getByteArrayExtra("salt")),
            challenge,
        )
}
