package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.transformShapes
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.wizard.WizardPreview
import com.artemchep.keyguard.ui.wizard.renderScene
import kotlinx.coroutines.flow.emptyFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Renders the screen body without app services; PNGs land in `build/reports/client-certificate`. */
class AccountClientCertificateScreenTest {
    @Test
    fun `screen states render and mark only a change as not saved`() {
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                renderScene(
                    folder = "build/reports/client-certificate",
                    name = variant.name,
                    width = 412,
                    height = variant.height,
                    fontScale = variant.fontScale,
                    rtl = variant.rtl,
                    content = {
                        WizardPreview(dark = variant.dark, expressive = variant.expressive) {
                            AccountClientCertificateContent(variant.state)
                        }
                    },
                ) { nodes ->
                    val texts = nodes.texts()
                    val configured = variant.state.certificate != null
                    assertFalse(texts.any { it.startsWith("Expires in") }, "${variant.name}: no status text")
                    assertTrue(
                        texts.any { it == if (configured) "Remove certificate" else "Add certificate" },
                        "${variant.name}: offers the primary action, got $texts",
                    )
                    assertTrue(
                        texts.any { it == "Not saved" } == variant.state.unsaved,
                        "${variant.name}: marks only a change as not saved, got $texts",
                    )
                    if (configured && variant.state.certificate?.details?.isNotEmpty() == true) {
                        assertTrue(texts.any { it.contains("12:AB:9F:00") }, "${variant.name}: shows the fingerprint")
                    }
                    if (!configured) assertFalse(texts.any { it == "Remove certificate" })
                }
            }
        }
    }

    private val variants = listOf(
        Variant("empty", state(certificate = null)),
        Variant("valid", state(certificate(ClientCertificateStatus.VALID, 0.35f))),
        Variant(
            "expiring-unsaved-dark",
            state(certificate(ClientCertificateStatus.EXPIRING_SOON, 0.97f), unsaved = true),
            dark = true,
        ),
        Variant("expired", state(certificate(ClientCertificateStatus.EXPIRED, 1f))),
        Variant(
            "unreadable-error",
            state(
                certificate(status = null, progress = null),
                note = SimpleNote("Failed to import", SimpleNote.Type.ERROR),
            ),
        ),
        Variant(
            "valid-standard",
            state(certificate(ClientCertificateStatus.VALID, 0.35f)),
            expressive = false,
        ),
        Variant(
            "rtl-large-text",
            state(certificate(ClientCertificateStatus.EXPIRED, 1f), unsaved = true),
            fontScale = 1.6f,
            rtl = true,
            // Lazy rows below the viewport are not composed.
            height = 2400,
        ),
    )

    private data class Variant(
        val name: String,
        val state: AccountClientCertificateState,
        val dark: Boolean = false,
        val expressive: Boolean = true,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val height: Int = 1400,
    )

    private fun List<SemanticsNode>.texts() = flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun state(
        certificate: AccountClientCertificateState.Certificate?,
        unsaved: Boolean = false,
        note: SimpleNote? = null,
    ) = AccountClientCertificateState(
        certificate = certificate,
        unsaved = unsaved,
        note = note,
        busy = false,
        onImport = {},
        onFileDrop = {},
        onRemove = {}.takeIf { certificate != null },
        onSave = {}.takeIf { unsaved },
        confirmRemoval = false,
        onConfirmRemoval = {},
        onDismissRemoval = {},
        filePickerIntents = emptyFlow(),
    )

    private fun certificate(
        status: ClientCertificateStatus?,
        progress: Float?,
    ) = AccountClientCertificateState.Certificate(
        displayName = if (status != null) "vault-client" else "Client certificate",
        issuerName = "Example Root CA".takeIf { status != null },
        status = status,
        validFrom = "Jan 2, 2026".takeIf { progress != null },
        expiresOn = "Jul 1, 2027".takeIf { progress != null },
        progress = progress,
        details = if (status == null) emptyList() else details(),
    )

    private fun details() = listOf(
        value("subject", "Subject", "CN=vault-client,O=Example"),
        value("issuer", "Issuer", "CN=Example Root CA,O=Example"),
        value("serial", "Serial number", "\u206604:A1:9C:7E\u2069", monospace = true),
        value(
            "fingerprint",
            "SHA-256 fingerprint",
            "\u2066" +
                "12:AB:9F:00 3C:DE:41:7A 88:B2:5E:61 C0:9D:24:F7 3A:6B:11:E8 52:0C:BD:94 7F:23:A6:58 E1:0B:C7:3D" +
                "\u2069",
            monospace = true,
        ),
        value("key_type", "Key type", "EC P-256"),
    ).transformShapes()

    private fun value(
        id: String,
        title: String,
        value: String,
        monospace: Boolean = false,
    ) = VaultViewItem.Value(
        id = "certificate.$id",
        title = title,
        value = value,
        monospace = monospace,
        dropdown = listOf(
            FlatItemAction(
                icon = Icons.Outlined.ContentCopy,
                title = TextHolder.Value("Copy"),
                type = FlatItemAction.Type.COPY,
            ),
        ),
    )
}
