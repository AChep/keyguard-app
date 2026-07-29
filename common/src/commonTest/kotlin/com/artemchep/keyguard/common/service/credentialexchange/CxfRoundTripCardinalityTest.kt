package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many vault items go in versus how many create requests come out.
 *
 * A CXF item is not one-to-one with a Keyguard item in either direction: one
 * vault item can carry a login *and* a card *and* an identity *and* an ssh key,
 * and the importer rebuilds one request per payload. Item-level fidelity lives
 * in `CxfRoundTripViewTest`, which compares whole projected views; what is left
 * here is where the leftovers land and the counts that view cannot express.
 *
 * The last test is load-bearing for the whole round-trip suite: a multi-item
 * document is exactly the concatenation of the single-item ones, which is what
 * licenses `CxfRoundTripHarness.views` to project one item at a time —
 * `CreateRequest` carries no source id to attribute a mixed plan with.
 */
class CxfRoundTripCardinalityTest {
    private val harness = CxfRoundTripHarness()

    private val everythingSecret = cxfSecret(
        type = DSecret.Type.Login,
        login = DSecret.Login(username = "alice", password = "s3cr3t"),
        card = DSecret.Card(number = "4111", expMonth = "5", expYear = "2027"),
        identity = DSecret.Identity(firstName = "Alice", city = "Springfield"),
        sshKey = DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
        notes = "shared note",
    )

    private fun requests(secret: DSecret): List<CreateRequest> = harness
        .roundTrip(listOf(secret))
        .plan
        .items
        .map { it.request }

    @Test
    fun `leftover fields attach to the first request only`() {
        // Not to the semantically matching one — whichever request came first.
        val requests = requests(
            everythingSecret.copy(
                fields = listOf(
                    DSecret.Field(name = "Extra", value = "v", type = DSecret.Field.Type.Text),
                ),
            ),
        )
        // The "only" is load-bearing, so the case must really cross the export
        // leg with several requests.
        assertEquals(
            listOf(
                DSecret.Type.Login,
                DSecret.Type.Card,
                DSecret.Type.Identity,
                DSecret.Type.SshKey,
            ),
            requests.map { it.type },
        )
        assertEquals(listOf("Extra"), requests.first().fields.map { it.name })
        assertTrue(
            requests.drop(1).all { it.fields.isEmpty() },
            "only the first request may carry the leftovers",
        )
    }

    @Test
    fun `leftover fields land on a card when there is no login`() {
        val requests = requests(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(number = "4111"),
                fields = listOf(
                    DSecret.Field(name = "Extra", value = "v", type = DSecret.Field.Type.Text),
                ),
            ),
        )
        assertEquals(DSecret.Type.Card, requests.single().type)
        assertEquals(listOf("Extra"), requests.single().fields.map { it.name })
    }

    @Test
    fun `two field bags on the wire concatenate into one`() {
        // An identity item with its own custom fields emits *two* custom-fields
        // credentials; the importer merges them, so a duplicate is never counted.
        val result = harness.roundTrip(
            listOf(
                cxfSecret(
                    type = DSecret.Type.Identity,
                    identity = DSecret.Identity(firstName = "Alice", company = "Acme"),
                    fields = listOf(
                        DSecret.Field(name = "Extra", value = "v", type = DSecret.Field.Type.Text),
                    ),
                ),
            ),
        )
        assertEquals(cxfImportSkips(), result.plan.skips)
        val request = result.plan.items.single().request
        assertEquals("Acme", request.identity.company)
        assertEquals(listOf("Extra"), request.fields.map { it.name })
    }

    @Test
    fun `a folder bearing export imports with an empty tally`() {
        // The element-wise collection decode must not invent a loss for a
        // document Keyguard itself wrote — including its nested collections.
        val result = harness.roundTrip(
            secrets = listOf(
                cxfSecret(
                    type = DSecret.Type.Login,
                    login = DSecret.Login(username = "alice", password = "s3cr3t"),
                    folderId = "child",
                ),
            ),
            folders = listOf(
                cxfFolder(id = "parent", name = "Work"),
                cxfFolder(id = "child", name = "Work/Personal"),
            ),
        )
        assertEquals(cxfImportSkips(), result.plan.skips)
        assertEquals(listOf("Work", "Personal"), result.plan.folders.map { it.title })
        assertTrue(result.plan.items.single().folderKey != null)
    }

    @Test
    fun `an all-empty passkey is one in and zero out`() {
        // A passkey whose credential id, user handle and key are all empty can
        // never produce an assertion, so the export refuses it instead of putting
        // a degenerate credential on the wire for the importer to accept.
        val result = harness.roundTrip(
            listOf(
                cxfLoginSecret(
                    login = DSecret.Login(
                        fido2Credentials = listOf(
                            cxfFido2Credential(credentialId = "", userHandle = "", keyValue = ""),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(cxfExportSkips(CxfExportSkipReason.Passkey to 1), result.exportSkips)
        assertEquals(emptyList(), result.plan.items)
    }

    @Test
    fun `a multi item plan is the concatenation of the per item plans`() {
        // Items do not influence each other's mapping — including the GpgKey
        // item, which yields nothing at all and must not disturb its
        // neighbours.
        val secrets = listOf(
            cxfLoginSecret(id = "a", login = DSecret.Login(username = "alice", password = "p1")),
            cxfSecret(id = "b", type = DSecret.Type.Card, card = DSecret.Card(number = "4111")),
            everythingSecret.copy(id = "c"),
            cxfSecret(id = "gone", type = DSecret.Type.GpgKey),
            cxfSecret(id = "d", type = DSecret.Type.SecureNote, notes = "note"),
        )
        val together = harness.roundTrip(secrets).plan
        val separately = secrets.flatMap { harness.roundTrip(listOf(it)).plan.items }
        assertEquals(
            separately.map { it.request },
            together.items.map { it.request },
        )
        assertEquals(separately.size, together.items.size)
    }
}
