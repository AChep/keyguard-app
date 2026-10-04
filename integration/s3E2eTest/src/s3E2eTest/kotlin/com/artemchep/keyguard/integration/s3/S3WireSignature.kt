package com.artemchep.keyguard.integration.s3

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

/** Independent JDK implementation: verifies the bytes captured from the socket. */
internal fun S3HttpRequest.verifySignature() {
    val authorization = headers.getValue("authorization")
    val credential = authorization.substringAfter("Credential=").substringBefore(',')
    val scope = credential.substringAfter('/')
    val signedNames = authorization.substringAfter("SignedHeaders=").substringBefore(',')
    val signature = authorization.substringAfter("Signature=")
    assertEquals("test-access", credential.substringBefore('/'))
    assertEquals("us-east-1/s3/aws4_request", scope.substringAfter('/'))
    assertEquals(body.sha256(), headers.getValue("x-amz-content-sha256"))
    val canonicalHeaders = signedNames.split(';').joinToString("") {
        "$it:${headers.getValue(it).trim().replace(Regex("\\s+"), " ")}\n"
    }
    val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.sorted().joinToString("&")
    val canonical = listOf(
        method, target.substringBefore('?'), query, canonicalHeaders, signedNames, body.sha256(),
    ).joinToString("\n")
    val signingText = listOf(
        "AWS4-HMAC-SHA256", headers.getValue("x-amz-date"), scope, canonical.encodeToByteArray().sha256(),
    ).joinToString("\n")
    val dateKey = hmac("AWS4test-secret".encodeToByteArray(), scope.substringBefore('/'))
    val regionKey = hmac(dateKey, "us-east-1")
    val serviceKey = hmac(regionKey, "s3")
    val key = hmac(serviceKey, "aws4_request")
    assertEquals(hmac(key, signingText).toHexString(), signature, "$method $target")
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()

private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(value.encodeToByteArray())
}
