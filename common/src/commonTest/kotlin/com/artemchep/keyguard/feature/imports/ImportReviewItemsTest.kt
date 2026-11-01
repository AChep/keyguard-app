package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.cxfFido2Credential
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.feature.transfer.TransferItem
import com.artemchep.keyguard.provider.bitwarden.usecase.toCreateRequests
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.untitled
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The import review lists every item it is about to write, using the same rows as the
 * export review. These pin the projection from the planned vault writes onto those
 * rows — in particular that it describes what will be *written*, not what arrived.
 */
class ImportReviewItemsTest {
    @Test
    fun `items are sorted by their resolved titles and reshaped`() {
        val items = reviewItems(
            request(title = "Zulu", type = DSecret.Type.Login),
            request(title = "Alpha", type = DSecret.Type.Card),
            request(title = "Middle", type = DSecret.Type.SecureNote),
        )

        assertEquals(listOf("Alpha", "Middle", "Zulu"), items.map { it.title })
        assertEquals(
            listOf(ShapeState.START, ShapeState.CENTER, ShapeState.END),
            items.map { it.shapeState },
        )
    }

    @Test
    fun `a login carries its password, passkey and one-time password badges`() {
        val item = reviewItems(
            request(
                title = "Netflix",
                type = DSecret.Type.Login,
                login = CreateRequest.Login(
                    password = "hunter2",
                    totp = "otpauth://totp/Netflix?secret=JBSWY3DPEHPK3PXP",
                ),
                fido2Credentials = persistentListOf(cxfFido2Credential()),
            ),
        ).single()
        assertEquals("Netflix", item.title)
        assertEquals(
            listOf(
                TransferItem.Kind.Passkey,
                TransferItem.Kind.Password,
                TransferItem.Kind.Totp,
            ),
            item.credentials,
        )
    }

    @Test
    fun `an ssh key is badged after the custom fields`() {
        // Matches the enum's declaration order and the export wire, where the
        // ssh-key credential follows the user's custom fields.
        val item = reviewItems(
            request(
                title = "Deploy key",
                type = DSecret.Type.SshKey,
                fields = persistentListOf(
                    DSecret.Field(
                        name = "Comment",
                        value = "ci@example.com",
                        type = DSecret.Field.Type.Text,
                    ),
                ),
            ),
        ).single()
        assertEquals(
            listOf(
                TransferItem.Kind.Fields,
                TransferItem.Kind.SshKey,
            ),
            item.credentials,
        )
    }

    @Test
    fun `gpg keys and environments are badged and counted`() {
        val requests = arrayOf(
            request(
                title = "Signing key",
                type = DSecret.Type.GpgKey,
                fields = persistentListOf(
                    DSecret.Field(
                        name = "Comment",
                        value = "release",
                        type = DSecret.Field.Type.Text,
                    ),
                ),
            ),
            request(title = "Deploy", type = DSecret.Type.Environment),
        )
        assertEquals(
            listOf(
                listOf(TransferItem.Kind.Environment),
                listOf(
                    TransferItem.Kind.Fields,
                    TransferItem.Kind.GpgKey,
                ),
            ),
            reviewItems(*requests).map { it.credentials },
        )
        assertEquals(
            ImportState.Counts(
                gpgKeyCount = 1,
                environmentCount = 1,
            ),
            plan(*requests).toUiCounts(),
        )
    }

    @Test
    fun `a blank title becomes the placeholder rather than an empty row`() {
        // A CXF item title may legally be blank, and a row with no text at all reads
        // as a rendering bug.
        val items = reviewItems(
            request(title = "   ", type = DSecret.Type.SecureNote),
            request(title = null, type = DSecret.Type.SecureNote),
        )
        assertEquals(listOf(UNTITLED, UNTITLED), items.map { it.title })
    }

    @Test
    fun `the placeholder on the review is the title the commit writes`() {
        val requests = arrayOf(
            request(title = "   ", type = DSecret.Type.SecureNote),
            request(title = null, type = DSecret.Type.SecureNote),
            request(title = "Netflix", type = DSecret.Type.Login),
        )
        val written = plan(*requests).toCreateRequests(
            accountId = AccountId("acc-1"),
            folderIdByKey = emptyMap(),
            untitledTitle = UNTITLED,
        )
        assertEquals(reviewItems(*requests).map { it.title }, written.map { it.title })
        assertEquals(listOf(UNTITLED, UNTITLED, "Netflix"), written.map { it.title })
    }

    @Test
    fun `the placeholder is translated from the import resource`() {
        // An injected label does not verify which resource the producer translates.
        assertEquals(
            Res.string.untitled,
            importUntitledRes,
        )
    }

    @Test
    fun `an empty password is not badged as one`() {
        // The importer normalises an absent password to an empty string in places, so
        // the badge has to key off emptiness rather than nullness.
        val item = reviewItems(
            request(
                title = "Bookmark",
                type = DSecret.Type.Login,
                login = CreateRequest.Login(password = ""),
            ),
        ).single()
        assertEquals(emptyList(), item.credentials)
    }

    private fun request(
        title: String?,
        type: DSecret.Type,
        login: CreateRequest.Login = CreateRequest.Login(),
        fields: PersistentList<DSecret.Field> = persistentListOf(),
        fido2Credentials: PersistentList<DSecret.Login.Fido2Credentials> = persistentListOf(),
    ) = CreateRequest(
        title = title,
        type = type,
        login = login,
        fields = fields,
        fido2Credentials = fido2Credentials,
        now = NOW,
    )

    private fun plan(
        vararg requests: CreateRequest,
    ) = ImportPlan(
        source = ImportPlan.Source(
            displayName = "Exporter",
            identifier = "com.example.exporter",
        ),
        sourceAccountCount = 1,
        folders = emptyList(),
        items = requests.map { request ->
            ImportPlan.Item(
                request = request,
                folderKey = null,
            )
        },
        skips = importSkips(),
    )

    private fun reviewItems(
        vararg requests: CreateRequest,
    ): List<TransferItem> {
        val review = Step.Review(
            plan = plan(*requests),
            sourcePackageName = "com.example.exporter",
            untitledLabel = UNTITLED,
        )
        return review.items.map { it.item }
    }
}

/**
 * A stand-in for the translated placeholder. The projection must not depend on the
 * label's wording; the resource the producer translates is tested separately.
 */
private const val UNTITLED = "(none)"
private val NOW = Instant.parse("2024-01-30T14:09:33Z")
