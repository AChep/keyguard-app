package com.artemchep.keyguard.integration.wearcredentialproviderapp

import android.app.Activity
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class WearCredentialClient(
    private val activity: Activity,
    private val credentialManager: CredentialManager,
) {
    suspend fun getCredential(
        preset: CredentialRequestPreset,
    ): CredentialResult {
        val response = credentialManager.getCredential(
            context = activity,
            request = preset.toRequest(),
        )

        return when (val credential = response.credential) {
            is PasswordCredential -> credential.toResult()
            is PublicKeyCredential -> credential.toResult()
            else -> credential.toResult()
        }
    }

    private fun CredentialRequestPreset.toRequest(): GetCredentialRequest =
        GetCredentialRequest.Builder()
            .setCredentialOptions(options())
            .build()

    private fun CredentialRequestPreset.options() = buildList {
        if (includesPassword) {
            add(GetPasswordOption())
        }
        passkeyRpId?.let { rpId ->
            add(
                GetPublicKeyCredentialOption(
                    requestJson = buildPasskeyRequestJson(rpId = rpId),
                ),
            )
        }
    }

    private fun buildPasskeyRequestJson(
        rpId: String,
    ): String = JSONObject()
        .put("challenge", generateChallenge())
        .put("rpId", rpId)
        .put("userVerification", "preferred")
        .toString()

    private fun generateChallenge(): String {
        val challenge = ByteArray(32)
        secureRandom.nextBytes(challenge)
        return Base64.encodeToString(
            challenge,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }

    private fun PasswordCredential.toResult(): CredentialResult =
        CredentialResult(
            credentialType = type,
            headline = "Password credential",
            fields = listOf(
                "Username" to id,
                "Password" to password,
            ),
            rawJson = null,
        )

    private fun PublicKeyCredential.toResult(): CredentialResult {
        val json = authenticationResponseJson.toPrettyJson()
        val fields = buildList {
            json.extractString("id")?.let { add("Credential ID" to it) }
            json.extractString("type")?.let { add("Type" to it) }
            json.extractString("authenticatorAttachment")?.let {
                add("Attachment" to it)
            }
            json.extractNestedString("response", "userHandle")?.let {
                add("User handle" to it)
            }
        }

        return CredentialResult(
            credentialType = type,
            headline = "Passkey credential",
            fields = fields,
            rawJson = json,
        )
    }

    private fun Credential.toResult(): CredentialResult =
        CredentialResult(
            credentialType = type,
            headline = "Custom credential",
            fields = emptyList(),
            rawJson = JSONObject()
                .apply {
                    data.keySet().forEach { key ->
                        put(key, data.get(key)?.toString().orEmpty())
                    }
                }
                .toString(2),
        )

    private fun String.toPrettyJson(): String = try {
        JSONObject(this).toString(2)
    } catch (_: JSONException) {
        try {
            JSONArray(this).toString(2)
        } catch (_: JSONException) {
            this
        }
    }

    private fun String.extractString(
        key: String,
    ): String? = runCatching {
        JSONObject(this).optString(key).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun String.extractNestedString(
        parentKey: String,
        childKey: String,
    ): String? = runCatching {
        JSONObject(this)
            .optJSONObject(parentKey)
            ?.optString(childKey)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        private val secureRandom = SecureRandom()
    }
}

@Composable
fun rememberWearCredentialClient(): WearCredentialClient {
    val context = LocalContext.current
    val activity = context as Activity
    return remember(activity) {
        WearCredentialClient(
            activity = activity,
            credentialManager = CredentialManager.create(activity),
        )
    }
}
