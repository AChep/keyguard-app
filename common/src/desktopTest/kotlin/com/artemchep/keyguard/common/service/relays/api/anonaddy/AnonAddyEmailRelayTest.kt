package com.artemchep.keyguard.common.service.relays.api.anonaddy

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnonAddyEmailRelayTest {
    @Test
    fun `generate preserves default routing for missing and blank recipients`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        recordingClient(requests).use { client ->
            val relay = AnonAddyEmailRelay(client)
            for (recipientId in listOf(null, "", " \t\n ")) {
                val config = recipientId?.let { validConfig.put("recipientId", it) } ?: validConfig
                val alias = relay.generate(GeneratorContext(host = "example.com"), config).bind()

                assertEquals("alias@anonaddy.me", alias)
            }
        }

        val expected = RecordedRequest(
            method = HttpMethod.Post,
            url = "https://app.addy.io/api/v1/aliases",
            authorization = "Bearer MY_TOKEN",
            contentType = ContentType.Application.Json.toString(),
            body = buildJsonObject {
                put("domain", "anonaddy.me")
                put("description", "example.com")
            },
        )
        assertEquals(List(3) { expected }, requests)
    }

    @Test
    fun `generate trims recipient and keeps configurations independent`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        recordingClient(requests).use { client ->
            val relay = AnonAddyEmailRelay(client)
            for (recipientId in listOf(" \t$RECIPIENT_A\n", RECIPIENT_B, null)) {
                val config = recipientId?.let { validConfig.put("recipientId", it) } ?: validConfig
                relay.generate(GeneratorContext(host = "example.com"), config).bind()
            }
        }

        assertEquals(
            listOf(listOf(RECIPIENT_A), listOf(RECIPIENT_B), null),
            requests.map { request ->
                request.body["recipient_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
            },
        )
        assertEquals(List(3) { "anonaddy.me" }, requests.map { it.body["domain"]?.jsonPrimitive?.content })
    }

    @Test
    fun `generate sends recipient to self hosted server`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        recordingClient(requests).use { client ->
            val relay = AnonAddyEmailRelay(client)
            for (baseUrl in listOf("https://addy.example.com", " https://addy.example.com/ ")) {
                relay.generate(
                    context = GeneratorContext(host = null),
                    config = validConfig
                        .put("base_url", baseUrl)
                        .put("recipientId", RECIPIENT_A),
                ).bind()
            }
        }

        assertEquals(List(2) { "https://addy.example.com/api/v1/aliases" }, requests.map { it.url })
        assertEquals(
            List(2) { listOf(RECIPIENT_A) },
            requests.map { it.body.getValue("recipient_ids").jsonArray.map { id -> id.jsonPrimitive.content } },
        )
    }

    @Test
    fun `generate surfaces rejected recipient without retrying default routing`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        recordingClient(
            requests = requests,
            responseStatus = HttpStatusCode.UnprocessableEntity,
            responseContent = """
                {"message":"Invalid Recipient","errors":{"recipient_ids":["Invalid Recipient"]}}
            """.trimIndent(),
        ).use { client ->
            val relay = AnonAddyEmailRelay(client)
            val error = assertFailsWith<IllegalArgumentException> {
                relay.generate(
                    context = GeneratorContext(host = "example.com"),
                    config = validConfig.put("recipientId", RECIPIENT_A),
                ).bind()
            }

            assertEquals("Invalid Recipient", error.message)
        }

        assertEquals(
            listOf(RECIPIENT_A),
            requests.single().body.getValue("recipient_ids").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        responseStatus: HttpStatusCode = HttpStatusCode.Created,
        responseContent: String = """{"data":{"email":"alias@anonaddy.me"}}""",
    ) = HttpClient(
        MockEngine { request ->
            requests += RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                contentType = request.body.contentType?.toString(),
                body = apiJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject,
            )
            respond(
                content = responseContent,
                status = responseStatus,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(apiJson))
        }
    }

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val authorization: String?,
        val contentType: String?,
        val body: JsonObject,
    )

    private companion object {
        val apiJson = createApplicationJson()
        val validConfig = persistentMapOf("apiKey" to "MY_TOKEN", "domain" to "anonaddy.me")
        const val RECIPIENT_A = "46eebc50-f7f8-46d7-beb9-c37f04c29a84"
        const val RECIPIENT_B = "50c9e585-e7f5-41c4-9016-9014c15454bc"
    }
}
