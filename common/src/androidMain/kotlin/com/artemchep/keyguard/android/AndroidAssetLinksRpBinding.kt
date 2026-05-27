package com.artemchep.keyguard.android

import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PERMISSION_GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"

internal class AndroidAssetLinksRpBinding(
    httpClient: HttpClient,
) {
    private val httpClientNoRedirect = httpClient.config {
        followRedirects = false
    }

    suspend fun requireRpMatchesOrigin(
        rpId: String,
        origin: String,
        packageName: String,
    ) {
        val certB64 = origin.substringAfter("android:apk-key-hash:")
        require(certB64 != origin) {
            "Request origin has unknown android format."
        }

        val certBytes = PasskeyBase64.decode(certB64)

        fun validateAndroidAppTarget(t: AndroidAppTarget): Boolean {
            return t.fingerprints
                .any { fingerprint ->
                    fingerprint.contentEquals(certBytes)
                }
        }

        val androidAppGetLoginCredsRelation = AndroidAppRelation(PERMISSION_GET_LOGIN_CREDS)
        val androidAppPackageName = AndroidAppPackageName(packageName)
        val targets = obtainAllowedAndroidAppTargets(rpId = rpId)
        val target = targets[androidAppGetLoginCredsRelation]
            ?.get(androidAppPackageName)
        requireNotNull(target) {
            val details = targets
                .entries
                .mapNotNull { (relation, appTarget) ->
                    val t = appTarget[androidAppPackageName]
                        ?: return@mapNotNull null
                    val valid = validateAndroidAppTarget(t)

                    val marker = if (valid) "\u2705" else "\uD83D\uDEAB"
                    "$marker ${relation.relation};"
                }
            if (details.isNotEmpty()) {
                val list = details.joinToString("\n")
                return@requireNotNull "App package name is associated with the " +
                        "relying party, however none of the specified relations allow sharing " +
                        "credentials:\n$list\n\nThe $PERMISSION_GET_LOGIN_CREDS is " +
                        "missing."
            }

            "App package name is not associated with the " +
                    "relying party!"
        }

        val valid = target.fingerprints.any {
            it.contentEquals(certBytes)
        }
        require(valid) {
            "App package name is associated with the relying party, " +
                    "however none of the specified signatures do match the app!"
        }
    }

    private suspend fun obtainAllowedAndroidAppTargets(
        rpId: String,
    ): Map<AndroidAppRelation, Map<AndroidAppPackageName, AndroidAppTarget>> {
        val url = "https://$rpId/.well-known/assetlinks.json"
        val response = httpClientNoRedirect
            .get(url)
        require(response.status.isSuccess()) {
            "Failed to get asset links from the relying party, " +
                    "error code ${response.status.value} ${response.status.description}."
        }
        val result = response
            .body<JsonElement>()

        val collector = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        val list = result as JsonArray
        list.forEach {
            runCatching {
                val node = it as JsonObject
                val relationJsonArray = node["relation"] as JsonArray
                val targetJsonObject = node["target"] as JsonObject

                val namespace = targetJsonObject["namespace"]?.jsonPrimitive?.content
                if (namespace != "android_app") {
                    return@forEach
                }
                val relations = relationJsonArray
                    .asSequence()
                    .mapNotNull { el ->
                        runCatching {
                            el.jsonPrimitive.content
                        }.getOrNull()
                    }
                    .toSet()
                if (relations.isEmpty()) {
                    return@forEach
                }

                val packageName = targetJsonObject["package_name"]?.jsonPrimitive?.content
                requireNotNull(packageName)
                val fingerprints = kotlin.run {
                    val arr = targetJsonObject["sha256_cert_fingerprints"] as JsonArray
                    arr.map { el -> el.jsonPrimitive.content }
                }

                relations.forEach { relation ->
                    val relationGroup = collector.getOrPut(relation) { mutableMapOf() }
                    val packageNameGroup = relationGroup.getOrPut(packageName) { mutableSetOf() }
                    packageNameGroup.addAll(fingerprints)
                }
            }
        }
        return collector
            .entries
            .associate { (relation, appTarget) ->
                val androidAppRelation = AndroidAppRelation(relation)
                val androidAppTarget = appTarget
                    .entries
                    .associate { (packageName, fingerprints) ->
                        val androidAppPackageName = AndroidAppPackageName(packageName)
                        val androidAppTarget = AndroidAppTarget(
                            fingerprints = fingerprints
                                .map { fingerprintHex ->
                                    val rawFingerprintHex = fingerprintHex
                                        .lowercase()
                                        .replace(":", "")
                                    rawFingerprintHex.hexToByteArray()
                                },
                        )
                        androidAppPackageName to androidAppTarget
                    }
                androidAppRelation to androidAppTarget
            }
    }

    @JvmInline
    private value class AndroidAppRelation(
        val relation: String,
    )

    @JvmInline
    private value class AndroidAppPackageName(
        val packageName: String,
    )

    private class AndroidAppTarget(
        val fingerprints: List<ByteArray>,
    )
}
