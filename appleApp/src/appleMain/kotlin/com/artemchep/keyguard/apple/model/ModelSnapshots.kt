package com.artemchep.keyguard.apple.model

import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.left
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.formatCardNumber
import com.artemchep.keyguard.feature.home.vault.component.obscureCardNumber
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import com.artemchep.keyguard.feature.home.vault.screen.RichBadge
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.vault.toSnapshot
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.totp.TotpCodeState
import com.artemchep.keyguard.ui.totp.totpCodeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus

/** One option of a settings picker (dropdown). [id] is an opaque, stable key. */
data class SettingOptionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/**
 * A single text field projected for SwiftUI. [id] is an opaque routing key the
 * UI passes back to [KeyguardCore.setLoginField]; [label] is set for the dynamic
 * custom-environment URL / header fields and null for email / password / secret.
 */
/**
 * The generic, Swift-facing projection of a shared [TextFieldState]: a pure
 * value (no closures) that every field-bearing screen snapshot embeds. The
 * live edit closure is kept Kotlin-side, keyed by [id] (the field's
 * producer-assigned identity); SwiftUI echoes [id] back through the owning
 * screen's `set…` method to reach it. [vlType] / [vlText] carry the optional
 * inline validation banner (severity name + message).
 */
data class TextFieldSnapshot(
    val id: String,
    val text: String,
    val textRevision: Int,
    val placeholder: String?,
    val error: String?,
    val vlType: String?,
    val vlText: String?,
    val editable: Boolean,
) {
    companion object {
        fun empty(id: String = "") = TextFieldSnapshot(
            id = id,
            text = "",
            textRevision = 0,
            placeholder = null,
            error = null,
            vlType = null,
            vlText = null,
            editable = false,
        )
    }
}

/**
 * Pure projection of a [TextFieldModel] into a [TextFieldSnapshot]. [id]
 * defaults to the field's producer-assigned [TextFieldState.id]; pass an
 * explicit id for fields whose producer does not yet assign one.
 */
internal fun TextFieldModel.toFieldSnapshot(
    id: String = state.id,
    placeholder: String? = hint,
): TextFieldSnapshot = TextFieldSnapshot(
    id = id,
    text = text,
    textRevision = textRevision,
    placeholder = placeholder,
    error = error,
    vlType = vl?.type?.name,
    vlText = vl?.text,
    editable = onChange != null,
)

/**
 * Like [toFieldSnapshot] but also registers the field's live [onChange] into
 * [handlers] under the snapshot [id], so the owning screen's `set…(id, text)`
 * method can route an edit back to the producer.
 */
internal fun TextFieldModel.toFieldSnapshot(
    handlers: MutableMap<String, (String) -> Unit>,
    id: String = state.id,
    placeholder: String? = hint,
): TextFieldSnapshot {
    onChange?.let { handlers[id] = it }
    return toFieldSnapshot(id = id, placeholder = placeholder)
}

/**
 * Maps the shared `List<VaultViewItem>` produced by both the vault-view and the
 * send-view state producers into the flat `List<VaultItemSnapshot>` the SwiftUI
 * detail screens render. Side-effect: registers every item action closure into
 * [actionHandlers] under a synthesized opaque id so SwiftUI can invoke it back
 * by id alone. [notesText] supplies the Markdown note body (the cipher's or the
 * send's `notes`), which the `Note` item only carries as a rendering flag.
 */
internal suspend fun buildVaultItemSnapshots(
    items: List<VaultViewItem>,
    notesText: String?,
    leContext: LeContext,
    actionHandlers: MutableMap<String, () -> Unit>,
    // Live per-second TOTP badge state keyed by item id. Only the cipher detail
    // supplies this; Send / Account details carry no TOTP rows.
    totpStates: Map<String, TotpFieldSnapshot> = emptyMap(),
    // The Gravatar URL of the cipher's username, rendered as the avatar of the
    // "login.username" value row. Only the cipher detail supplies this, and only
    // while the shared Gravatar preference is enabled.
    usernameAvatarUrl: String? = null,
    // Per-field reveal gating for concealed VALUE / CARD rows, supplied only by the
    // cipher detail. [revealedIds] is the set of item ids the user has already
    // revealed (after passing the producer's `executeWithRePrompt` gate, which fires
    // the elevated-access dialog on a master-password-reprompt cipher). [onRequestReveal]
    // registers the gated reveal closure: it MUST route through the shared
    // `Visibility.transformUserEvent` so the master-password / biometric prompt fires
    // BEFORE the value is disclosed, exactly like the copy action.
    //
    // When null (Send / Account details, which carry no concealed reprompt fields),
    // concealed rows fall back to the legacy behavior — the value is sent and SwiftUI
    // masks it locally — so those surfaces are entirely unaffected.
    revealedIds: Set<String>? = null,
    onRequestReveal: ((VaultViewItem) -> String?)? = null,
    uriAppIcons: Map<VaultUriIcon.App, String?> = emptyMap(),
): List<VaultItemSnapshot> {
    val out = ArrayList<VaultItemSnapshot>()

    suspend fun List<ContextItem>.toActionSnapshots(itemId: String): List<VaultActionSnapshot> {
        val keys = ActionKeyAllocator(itemId)
        val acc = ArrayList<VaultActionSnapshot>()
        for (ci in this) {
            if (ci !is FlatItemAction) continue
            val title = textResource(ci.title, leContext)
            val actionId = keys.keyFor(ci, title)
            ci.onClick?.let { actionHandlers[actionId] = it }
            acc += VaultActionSnapshot(
                id = actionId,
                title = title,
                isCopy = ci.type == FlatItemAction.Type.COPY,
                iconName = ci.icon.toActionIconName(),
                danger = ci.danger,
            )
        }
        return acc
    }

    for (item in items) {
        when (item) {
            is VaultViewItem.Value -> {
                // Reveal gating: a concealed value (e.g. a password / CVV) on a
                // master-password-reprompt cipher must NOT have its plaintext sent to
                // SwiftUI until the producer reports it revealed. The reveal request
                // goes through the producer's `Visibility.transformUserEvent`, i.e. the
                // SAME `executeWithRePrompt` path the copy action uses — so the
                // elevated-access dialog fires before disclosure. A hidden field (policy
                // forbids ever seeing it) is never revealable.
                // A field hidden by org policy (Visibility.hidden) can NEVER be
                // revealed — Compose renders no toggle for it. In the cipher-detail
                // context (onRequestReveal != null) we lock it: withhold the plaintext
                // and emit no reveal toggle. Outside that context (legacy Send/Account)
                // there are no hidden-policy fields, so behaviour is unchanged.
                val revealLocked = item.visibility.concealed &&
                    item.visibility.hidden &&
                    onRequestReveal != null
                val gated = item.visibility.concealed &&
                    !item.visibility.hidden &&
                    onRequestReveal != null
                val isVisible = when {
                    revealLocked -> false
                    gated -> revealedIds?.contains(item.id) == true
                    else -> true
                }
                val revealActionId = if (gated) onRequestReveal?.invoke(item) else null
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.VALUE,
                    title = item.title,
                    // Withhold the plaintext while a gated field is still concealed, or
                    // permanently for a policy-locked field; only send it once the
                    // producer has flipped a gated field visible.
                    text = if (revealLocked || (gated && !isVisible)) null else item.value,
                    concealed = item.visibility.concealed,
                    monospace = item.monospace,
                    colorize = item.colorize,
                    launchUrl = null,
                    switchValue = false,
                    actions = item.dropdown.toActionSnapshots(item.id),
                    avatarUrl = usernameAvatarUrl.takeIf { item.id == "login.username" },
                    shapeState = item.shapeState,
                    isVisible = isVisible,
                    revealActionId = revealActionId,
                    revealLocked = revealLocked,
                )
            }

            is VaultViewItem.Totp -> {
                val local = item.localStateFlow.value
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.TOTP,
                    title = item.title,
                    text = null,
                    concealed = false,
                    monospace = true,
                    totp = totpStates[item.id] ?: TotpFieldSnapshot.loading,
                    launchUrl = null,
                    switchValue = false,
                    actions = local.dropdown.toActionSnapshots(item.id),
                    shapeState = item.shapeState,
                )
            }

            is VaultViewItem.Uri -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.URI,
                title = item.title.text,
                text = item.text,
                concealed = false,
                monospace = false,
                colorize = item.colorize,
                launchUrl = item.text,
                switchValue = false,
                actions = item.dropdown.toActionSnapshots(item.id),
                shapeState = item.shapeState,
                uriIcon = item.iconSource.toUriIconSnapshot(uriAppIcons),
            )

            is VaultViewItem.Note -> {
                val text = when (val c = item.content) {
                    is VaultViewItem.Note.Content.Markdown -> notesText
                    is VaultViewItem.Note.Content.Text -> c.text
                }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.NOTE,
                    title = null,
                    text = text,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = emptyList(),
                    markdown = item.content is VaultViewItem.Note.Content.Markdown,
                )
            }

            is VaultViewItem.Section -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.SECTION,
                title = item.text,
                text = null,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = emptyList(),
            )

            is VaultViewItem.Table -> {
                // A key/value table (GPG key metadata, SSH key details, generator key
                // type). SwiftUI has no dedicated table row yet, so flatten it: the
                // optional header becomes a SECTION, then each row is a monospace VALUE
                // (fingerprints / algorithms / key ids read best fixed-width).
                item.title?.let { title ->
                    out += VaultItemSnapshot(
                        id = "${item.id}:header",
                        kind = VaultItemKind.SECTION,
                        title = title,
                        text = null,
                        concealed = false,
                        monospace = false,
                        launchUrl = null,
                        switchValue = false,
                        actions = emptyList(),
                    )
                }
                item.rows.forEachIndexed { rowIndex, row ->
                    out += VaultItemSnapshot(
                        id = "${item.id}:row:$rowIndex",
                        kind = VaultItemKind.VALUE,
                        title = row.title,
                        text = row.value,
                        concealed = false,
                        monospace = true,
                        launchUrl = null,
                        switchValue = false,
                        actions = emptyList(),
                    )
                }
            }

            is VaultViewItem.Label -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.LABEL,
                title = null,
                text = item.text.text,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = emptyList(),
            )

            is VaultViewItem.Switch -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.TOGGLE,
                title = item.title,
                text = null,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = item.value,
                actions = item.dropdown.toActionSnapshots(item.id),
                shapeState = item.shapeState,
            )

            is VaultViewItem.Link -> {
                // Cipher links ("Linked items" / "Referenced by"). The native detail
                // screen has no dedicated linked-item row yet, so the link is projected
                // as a tappable ACTION row carrying the target's name / subtitle; the
                // click opens the linked cipher through the producer's own handler.
                val actionId = "${item.id}:onclick"
                item.onClick?.let { actionHandlers[actionId] = it }
                val title = item.presentation?.title?.text
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.ACTION,
                    title = title,
                    text = item.presentation?.text,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = if (item.onClick != null && title != null) {
                        listOf(VaultActionSnapshot(actionId, title, isCopy = false))
                    } else {
                        emptyList()
                    },
                    shapeState = item.shapeState,
                    clickActionId = actionId.takeIf { item.onClick != null },
                )
            }

            is VaultViewItem.Action -> {
                val actionId = "${item.id}:onclick"
                item.onClick?.let { actionHandlers[actionId] = it }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.ACTION,
                    title = item.title,
                    text = item.text,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = if (item.onClick != null) {
                        listOf(VaultActionSnapshot(actionId, item.title, isCopy = false))
                    } else {
                        emptyList()
                    },
                    shapeState = item.shapeState,
                )
            }

            is VaultViewItem.Button -> {
                val actionId = "${item.id}:onclick"
                actionHandlers[actionId] = item.onClick
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.BUTTON,
                    title = null,
                    text = item.text,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = listOf(VaultActionSnapshot(actionId, item.text, isCopy = false)),
                )
            }

            is VaultViewItem.Card -> {
                // The card visual header (mirrors the Compose `VaultViewCardItem`): the
                // brand / credit-card-type label, the formatted + obscured number, and
                // the cardholder name. The expiry / CVV / valid-from rows are emitted
                // SEPARATELY by the producer as their own localized VALUE rows, so we
                // do NOT re-flatten them here. The card-level copy / large-type / barcode
                // / share actions ride in [VaultViewItem.Card.dropdown]; project them so
                // the SwiftUI card draws the menu + copy shortcut just like every other
                // field row.
                val card = item.data
                val number = card.number
                // The card number reveal is gated identically to a Value field: the
                // toggle only appears when the number is concealed + present, and the
                // reveal request runs through `executeWithRePrompt`. While still
                // concealed we send ONLY the pre-obscured string (never the formatted
                // plaintext), so SwiftUI cannot reveal it without the producer.
                val numberConcealed = item.visibility.concealed && number != null
                // Policy-hidden card number: never revealable (no toggle), mirror Value.
                val revealLocked = numberConcealed &&
                    item.visibility.hidden &&
                    onRequestReveal != null
                val gated = numberConcealed &&
                    !item.visibility.hidden &&
                    onRequestReveal != null
                val isVisible = when {
                    revealLocked -> false
                    gated -> revealedIds?.contains(item.id) == true
                    else -> !numberConcealed
                }
                val revealActionId = if (gated) onRequestReveal?.invoke(item) else null
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.CARD,
                    title = null,
                    // [text] is the cardholder name shown under the number.
                    text = card.cardholderName,
                    concealed = numberConcealed,
                    monospace = true,
                    launchUrl = null,
                    switchValue = false,
                    actions = item.dropdown.toActionSnapshots(item.id),
                    shapeState = item.shapeState,
                    // The brand label, falling back to the resolved credit-card type
                    // (same choice as the Compose item).
                    cardBrand = card.brand ?: card.creditCardType?.name,
                    // Withhold the formatted (plaintext) number while gated + concealed;
                    // the obscured variant is always safe to send so the masked dots
                    // still render.
                    cardNumberFormatted = if (revealLocked || (gated && !isVisible)) {
                        null
                    } else {
                        number?.let(::formatCardNumber)
                    },
                    cardNumberObscured = number?.let(::obscureCardNumber),
                    isVisible = isVisible,
                    revealActionId = revealActionId,
                    revealLocked = revealLocked,
                )
            }

            is VaultViewItem.Identity -> {
                // The identity header (mirrors the Compose `VaultViewIdentityItem`): the
                // title and the joined full name, plus the quick-action buttons (call /
                // text / email / navigate-to-maps) the producer attaches as [actions].
                // Every individual identity field (name parts, contact info, misc,
                // address) is emitted SEPARATELY by the producer as a localized VALUE
                // row under its section header, so we do NOT re-flatten them here.
                val idn = item.data
                val name = listOfNotNull(idn.firstName, idn.middleName, idn.lastName)
                    .joinToString(separator = " ")
                    .ifBlank { null }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.IDENTITY,
                    // [title] is the identity title (Mr./Mrs./…), [text] the full name.
                    title = idn.title?.takeIf { it.isNotBlank() },
                    text = name,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = item.actions.toActionSnapshots(item.id),
                )
            }

            is VaultViewItem.ReusedPassword -> {
                val actionId = "${item.id}:onclick"
                actionHandlers[actionId] = item.onClick
                out += alertSnapshot(
                    item.id,
                    "Reused password",
                    "Used by ${item.count} items",
                    actionId,
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.ERROR,
                )
            }

            is VaultViewItem.InactiveTotp -> {
                val actionId = item.onClick?.let { "${item.id}:onclick".also { id -> actionHandlers[id] = it } }
                out += alertSnapshot(
                    item.id,
                    "Inactive one-time password",
                    null,
                    actionId,
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.WARNING,
                )
            }

            is VaultViewItem.InactivePasskey -> {
                val actionId = "${item.id}:onclick"
                actionHandlers[actionId] = item.onClick
                out += alertSnapshot(
                    item.id,
                    "Inactive passkey",
                    null,
                    actionId,
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.INFO,
                )
            }

            is VaultViewItem.Passkey -> {
                val useActions = item.onUse?.let { onUse ->
                    val actionId = "${item.id}:use"
                    actionHandlers[actionId] = onUse
                    listOf(VaultActionSnapshot(actionId, "Use", isCopy = false))
                } ?: emptyList()
                val clickActionId = item.onClick?.let { onClick ->
                    "${item.id}:onclick".also { id -> actionHandlers[id] = onClick }
                }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.PASSKEY,
                    title = item.value,
                    text = item.source.rpId,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = useActions,
                    shapeState = item.shapeState,
                    clickActionId = clickActionId,
                )
            }

            is VaultViewItem.Error -> {
                val actionId = item.onRetry?.let { "${item.id}:onclick".also { id -> actionHandlers[id] = it } }
                out += alertSnapshot(
                    item.id,
                    item.name,
                    item.message,
                    actionId,
                    actionTitle = "Retry",
                    shapeState = item.shapeState,
                )
            }

            is VaultViewItem.Info -> {
                out += alertSnapshot(item.id, item.name, item.message, null)
            }

            is VaultViewItem.Planeta -> {
                out += alertSnapshot(item.id, "Fingerprint", item.fingerprint, null)
            }

            is VaultViewItem.Tags -> {
                val actions = item.tags.mapIndexed { index, tag ->
                    val actionId = "${item.id}:tag:$index"
                    actionHandlers[actionId] = { item.onClick(tag) }
                    VaultActionSnapshot(actionId, tag, isCopy = false)
                }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.TAGS,
                    title = null,
                    text = null,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = actions,
                )
            }

            is VaultViewItem.Folder -> {
                // Breadcrumb nodes ride in [actions]; the row opens the folder.
                val nodes = item.nodes.mapIndexed { index, node ->
                    val actionId = "${item.id}:node:$index"
                    actionHandlers[actionId] = node.onClick
                    VaultActionSnapshot(actionId, node.name, isCopy = false)
                }
                val clickActionId = "${item.id}:onclick"
                    .also { id -> actionHandlers[id] = item.onClick }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.FOLDER,
                    title = null,
                    text = null,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = nodes,
                    shapeState = item.shapeState,
                    clickActionId = clickActionId,
                )
            }

            is VaultViewItem.Organization -> {
                val clickActionId = "${item.id}:onclick"
                    .also { id -> actionHandlers[id] = item.onClick }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.ORGANIZATION,
                    title = item.title,
                    text = null,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = emptyList(),
                    shapeState = item.shapeState,
                    clickActionId = clickActionId,
                )
            }

            is VaultViewItem.Collection -> {
                val clickActionId = "${item.id}:onclick"
                    .also { id -> actionHandlers[id] = item.onClick }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.COLLECTION,
                    title = item.title,
                    text = null,
                    concealed = false,
                    monospace = false,
                    launchUrl = null,
                    switchValue = false,
                    actions = emptyList(),
                    shapeState = item.shapeState,
                    clickActionId = clickActionId,
                )
            }

            is VaultViewItem.QuickActions -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.QUICK_ACTIONS,
                title = null,
                text = null,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = item.actions.toActionSnapshots(item.id),
            )

            is VaultViewItem.QuickBadges -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.QUICK_BADGES,
                title = null,
                text = null,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = emptyList(),
                badges = item.actions.map { badge ->
                    VaultBadgeSnapshot(
                        title = textResource(badge.title, leContext),
                        text = badge.text?.let { textResource(it, leContext) },
                    )
                },
            )

            is VaultViewItem.Attachment -> {
                val att = item.item
                val status = att.statusState.value
                val attachmentSnap = when (status) {
                    is AttachmentItem.Status.None -> AttachmentFieldSnapshot(
                        status = AttachmentStatusKind.NONE,
                        progress = 0f,
                        downloadedText = null,
                        autoResume = false,
                    )

                    is AttachmentItem.Status.Loading -> {
                        val downloaded = status.downloaded
                        val total = status.total
                        AttachmentFieldSnapshot(
                            status = AttachmentStatusKind.LOADING,
                            progress = if (downloaded != null && total != null && total > 0L) {
                                downloaded.toFloat() / total.toFloat()
                            } else {
                                -1f
                            },
                            downloadedText = downloaded?.let(::humanReadableByteCountSI),
                            autoResume = false,
                        )
                    }

                    is AttachmentItem.Status.Failed -> AttachmentFieldSnapshot(
                        status = AttachmentStatusKind.FAILED,
                        progress = 0f,
                        downloadedText = null,
                        autoResume = status.autoResume,
                    )

                    is AttachmentItem.Status.Downloaded -> AttachmentFieldSnapshot(
                        status = AttachmentStatusKind.DOWNLOADED,
                        progress = 1f,
                        downloadedText = null,
                        autoResume = false,
                    )

                    is AttachmentItem.Status.PendingUpload -> AttachmentFieldSnapshot(
                        status = AttachmentStatusKind.PENDING_UPLOAD,
                        progress = 0f,
                        downloadedText = null,
                        autoResume = false,
                    )
                }
                val clickActionId = att.preview?.onClick?.let { onClick ->
                    "${item.id}:onclick".also { id -> actionHandlers[id] = onClick }
                }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.ATTACHMENT,
                    title = att.name,
                    text = att.size,
                    concealed = false,
                    monospace = false,
                    launchUrl = (status as? AttachmentItem.Status.Downloaded)?.localUrl,
                    switchValue = false,
                    actions = att.actionsState.value.toActionSnapshots(item.id),
                    shapeState = item.shapeState,
                    clickActionId = clickActionId,
                    attachment = attachmentSnap,
                )
            }

            is VaultViewItem.Qr -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.QR,
                title = null,
                text = item.data,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = emptyList(),
            )

            is VaultViewItem.Spacer -> out += VaultItemSnapshot(
                id = item.id,
                kind = VaultItemKind.SPACER,
                title = null,
                text = null,
                concealed = false,
                monospace = false,
                launchUrl = null,
                switchValue = false,
                actions = emptyList(),
                spacerHeight = item.height.value,
            )
        }
    }

    return out
}

/**
 * Projects a list of top-level [FlatItemAction]s (the cipher detail's overflow /
 * toolbar actions) into flat [VaultActionSnapshot]s, registering each action's
 * [FlatItemAction.onClick] into [actionHandlers] under a synthesized opaque id
 * (`"$idPrefix:action:N"`) so SwiftUI can invoke it back by id alone. The same
 * id-routing scheme as the field-row actions in [buildVaultItemSnapshots]; pass a
 * distinct [idPrefix] (e.g. `"header"`) so the ids cannot collide with row ids.
 */
internal suspend fun List<FlatItemAction>.toHeaderActionSnapshots(
    idPrefix: String,
    leContext: LeContext,
    actionHandlers: MutableMap<String, () -> Unit>,
): List<VaultActionSnapshot> {
    val acc = ArrayList<VaultActionSnapshot>(size)
    val keys = ActionKeyAllocator(idPrefix)
    forEach { action ->
        val title = textResource(action.title, leContext)
        val actionId = keys.keyFor(action, title)
        action.onClick?.let { actionHandlers[actionId] = it }
        acc += VaultActionSnapshot(
            id = actionId,
            title = title,
            isCopy = action.type == FlatItemAction.Type.COPY,
            danger = action.danger,
        )
    }
    return acc
}

/**
 * Builds an [VaultItemKind.ALERT] row for the various warning / security / info
 * detail items. [actionId] (when present) is the synthesized id its caller has
 * already registered in [KeyguardCore.vaultActionHandlers].
 */
internal fun alertSnapshot(
    id: String,
    title: String,
    message: String?,
    actionId: String?,
    actionTitle: String = "Open",
    shapeState: Int = -1,
    severity: VaultAlertSeverity? = null,
): VaultItemSnapshot = VaultItemSnapshot(
    id = id,
    kind = VaultItemKind.ALERT,
    title = title,
    text = message,
    concealed = false,
    monospace = false,
    launchUrl = null,
    switchValue = false,
    actions = if (actionId != null) {
        listOf(VaultActionSnapshot(actionId, actionTitle, isCopy = false))
    } else {
        emptyList()
    },
    shapeState = shapeState,
    alertSeverity = severity,
)

/** Semantic emphasis for a security notice in the native detail UI. */
enum class VaultAlertSeverity {
    ERROR,
    WARNING,
    INFO,
}

/** Discriminator for the flat [VaultItemSnapshot] projected from a [VaultViewItem]. */
enum class VaultItemKind {
    VALUE,
    TOTP,
    URI,
    NOTE,
    SECTION,
    LABEL,
    TOGGLE,
    ACTION,
    BUTTON,
    ALERT,
    PASSKEY,
    TAGS,
    FOLDER,
    ORGANIZATION,
    COLLECTION,
    QUICK_ACTIONS,
    QUICK_BADGES,
    ATTACHMENT,
    QR,
    SPACER,
    // The credit-card visual header (brand + formatted/obscured number + cardholder),
    // mirroring the Compose `VaultViewCardItem`. The card-level copy / large-type /
    // barcode / share actions ride in [VaultItemSnapshot.actions]; the per-field rows
    // (expiry, CVV) are emitted separately by the producer as VALUE rows.
    CARD,
    // The identity header (title + full name) plus the quick-action buttons (call /
    // text / email / navigate), mirroring the Compose `VaultViewIdentityItem`. Every
    // individual identity field (name parts, contact info, misc, address) is emitted
    // separately by the producer as localized VALUE rows under section headers.
    IDENTITY,
    UNSUPPORTED,
}

/** A menu / button action; [id] routes back to [KeyguardCore.invokeVaultAction]. */
data class VaultActionSnapshot(
    val id: String,
    val title: String,
    val isCopy: Boolean,
    /**
     * SF Symbol name for the action's icon, mapped from the shared
     * [FlatItemAction.icon] via [toActionIconName]. Used by the identity header's
     * quick-action buttons (call / text / email / navigate) to draw the icon above
     * the label, mirroring the Compose `VaultViewIdentityItem`. `null` when the
     * source icon has no SF Symbol mapping (most menu/dropdown actions).
     */
    val iconName: String? = null,
    /**
     * `true` when this action opens a new visual group in an overflow menu — the
     * SwiftUI menu draws a divider before it. Set only by [buildMenuActionSnapshots]
     * (mirroring the Compose options-menu section dividers); the per-row / selection
     * action families leave it `false`.
     */
    val startsSection: Boolean = false,
    /**
     * For an overflow action backed by a toggle (the vault list's "always show
     * keyboard" / "remember sorting" switches), the toggle's current on/off state
     * — so the SwiftUI menu can render a Switch / checkmark, mirroring the Compose
     * options-menu `Switch`. `null` for plain actions. Set only by
     * [buildMenuActionSnapshots], which reads the producer's non-visual action id.
     */
    val switchState: Boolean? = null,
    /**
     * `true` for a destructive action (trash / delete / remove), from the shared
     * [FlatItemAction.danger], so SwiftUI can give it the destructive role without
     * inspecting its localized title.
     */
    val danger: Boolean = false,
)

/**
 * Maps a shared [FlatItemAction] Material [ImageVector] to the closest SF Symbol
 * name for the native Apple UI. Only the icons that actually surface as labelled
 * action buttons (the identity header's call / text / email / navigate actions)
 * are mapped; anything else returns `null` and the SwiftUI side falls back to a
 * text-only button. Keyed by [ImageVector.name] (e.g. `"Outlined.Call"`).
 */
private fun ImageVector?.toActionIconName(): String? = when (this?.name) {
    "Outlined.Call" -> "phone"
    "Outlined.Textsms" -> "message"
    "Outlined.Email" -> "envelope"
    "Outlined.Directions" -> "location.fill"
    else -> null
}

/** A display-only chip of a [VaultItemKind.QUICK_BADGES] row. */
data class VaultBadgeSnapshot(
    val title: String,
    val text: String?,
)

/** The download state of a [VaultItemKind.ATTACHMENT] row. */
enum class AttachmentStatusKind {
    NONE,
    LOADING,
    FAILED,
    DOWNLOADED,
    PENDING_UPLOAD,
}

/**
 * Live download state of a single attachment row, sampled from the shared
 * [AttachmentItem]'s state flows each time the snapshot stream rebuilds.
 * [progress] is the downloaded fraction (0..1), or -1 when indeterminate.
 */
data class AttachmentFieldSnapshot(
    val status: AttachmentStatusKind,
    val progress: Float,
    val downloadedText: String?,
    val autoResume: Boolean,
)

/**
 * The live state of a single TOTP badge, computed entirely by shared Kotlin
 * (the [totpCodeFlow] producer, shared with the Compose UI) and pushed to
 * SwiftUI every second so the macOS view only renders / animates it.
 *
 * [groups] is the code split into groups of code points (e.g. `[[1,2,3],[4,5,6]]`)
 * so the Swift view can draw the group separators and animate each digit. For a
 * time-based token [progress] is the fraction of the period remaining (1→0) and
 * [counterText] the whole seconds left; for a counter-based (HOTP) token [progress]
 * is 1 and [counterText] is the counter value.
 */
data class TotpFieldSnapshot(
    val groups: List<List<String>>,
    val codeRaw: String,
    val isLoading: Boolean,
    val isError: Boolean,
    val isTimeBased: Boolean,
    val counterText: String,
    val progress: Float,
) {
    companion object {
        val loading = TotpFieldSnapshot(
            groups = emptyList(),
            codeRaw = "",
            isLoading = true,
            isError = false,
            isTimeBased = false,
            counterText = "",
            progress = 1f,
        )
        val error = TotpFieldSnapshot(
            groups = emptyList(),
            codeRaw = "",
            isLoading = false,
            isError = true,
            isTimeBased = false,
            counterText = "",
            progress = 0f,
        )
    }
}

/**
 * Adapts the shared [totpCodeFlow] (the single source of truth for the live
 * code, countdown and progress, shared with the Compose UI) into the flat
 * [TotpFieldSnapshot] the SwiftUI view renders. Starts with
 * [TotpFieldSnapshot.loading] so the combine in
 * [KeyguardCore.observeCipherDetail] can emit before the first code arrives.
 */
internal fun totpBadgeFlow(
    getTotpCode: GetTotpCodeWithOffset,
    token: TotpToken,
): Flow<TotpFieldSnapshot> = totpCodeFlow(getTotpCode, token)
    .map { it.toFieldSnapshot() }
    .onStart { emit(TotpFieldSnapshot.loading) }

internal fun TotpCodeState.toFieldSnapshot(): TotpFieldSnapshot = when (this) {
    is TotpCodeState.Loading -> TotpFieldSnapshot.loading
    is TotpCodeState.Error -> TotpFieldSnapshot.error
    is TotpCodeState.Success -> {
        val groups = codes.map { group -> group.toList() }
        when (val counter = counter) {
            is TotpCodeState.Success.TimeBasedCounter -> TotpFieldSnapshot(
                groups = groups,
                codeRaw = codeRaw,
                isLoading = false,
                isError = false,
                isTimeBased = true,
                counterText = counter.time,
                progress = counter.progress,
            )

            is TotpCodeState.Success.IncrementBasedCounter -> TotpFieldSnapshot(
                groups = groups,
                codeRaw = codeRaw,
                isLoading = false,
                isError = false,
                isTimeBased = false,
                counterText = counter.counter,
                progress = 1f,
            )
        }
    }
}

/**
 * One flat row of the vault item detail. The populated fields depend on [kind];
 * [text] is the primary value (masked in the UI when [concealed]), [totp] holds
 * the live rendered code + countdown for [VaultItemKind.TOTP], and [launchUrl]
 * the open target for [VaultItemKind.URI].
 */
data class VaultItemSnapshot(
    val id: String,
    val kind: VaultItemKind,
    val title: String?,
    val text: String?,
    val concealed: Boolean,
    val monospace: Boolean,
    val totp: TotpFieldSnapshot? = null,
    val launchUrl: String?,
    val switchValue: Boolean,
    val actions: List<VaultActionSnapshot>,
    // NOTE only: true when the shared producer parsed the note as Markdown
    // (the "Render Markdown" preference is on and the body parses), so the
    // SwiftUI row renders it as rich text instead of plain text.
    val markdown: Boolean = false,
    // VALUE only: a directly-loadable avatar URL rendered before the value
    // (the username row's Gravatar; null when the preference is off).
    val avatarUrl: String? = null,
    // Card-grouping shape from the producer's transformShapes(): a ShapeState
    // bitmask (START/END/CENTER/ALL). -1 means the row is not grouped (plain).
    val shapeState: Int = -1,
    // Row-level onClick: opens the passkey credential sheet for PASSKEY, the
    // attachment preview for ATTACHMENT, and navigates (currently a no-op on
    // macOS) for FOLDER / ORGANIZATION / COLLECTION.
    val clickActionId: String? = null,
    val badges: List<VaultBadgeSnapshot> = emptyList(),
    val attachment: AttachmentFieldSnapshot? = null,
    // SPACER height in points (the Dp value of the shared item).
    val spacerHeight: Float = 0f,
    // CARD only: the brand / credit-card-type label drawn above the number (null when
    // unknown), and the number pre-formatted (with group spaces) for display + the
    // same number pre-obscured (leading digits replaced with •) so the masked vs.
    // revealed strings are computed by shared Kotlin, not re-derived in Swift. The
    // number's reveal toggle reuses [concealed]; [text] holds the cardholder name.
    val cardBrand: String? = null,
    val cardNumberFormatted: String? = null,
    val cardNumberObscured: String? = null,
    // VALUE / CARD reveal gating (mirrors the Compose per-field Visibility state):
    // whether the producer currently reports the concealed field as revealed. While a
    // concealed field is NOT visible, the bridge withholds the plaintext entirely
    // ([text] / [cardNumberFormatted] are null), so SwiftUI cannot leak it by flipping
    // a local @State. Always `true` for non-concealed rows, which carry their value as
    // before.
    val isVisible: Boolean = true,
    // VALUE / CARD only: the handler id (routed via [KeyguardCore.invokeVaultAction])
    // the eye toggle invokes to REQUEST a reveal. The handler runs the shared
    // producer's `Visibility.transformUserEvent`, i.e. the SAME `executeWithRePrompt`
    // path the copy action uses — so on a master-password-reprompt cipher the
    // elevated-access dialog fires FIRST, and only on success does the producer flip
    // visibility and re-emit the snapshot with the value + [isVisible] = true. `null`
    // for non-concealable rows; tapping it never discloses anything by itself.
    val revealActionId: String? = null,
    // VALUE / CARD only: the field is concealed by an org "hide passwords" policy
    // (Visibility.hidden) — the secret can NEVER be revealed (not even behind a
    // reprompt), exactly as Compose renders no reveal toggle for it. When true the
    // bridge withholds the plaintext ([text] / [cardNumberFormatted] are null),
    // [isVisible] is false, [revealActionId] is null, and SwiftUI must render the
    // masked value with NO eye toggle (and must NOT fall back to a local @State
    // reveal). Distinguishes a cipher-detail policy-masked field from a legacy
    // Send/Account concealed field (which keeps the local reveal toggle).
    val revealLocked: Boolean = false,
    // ALERT only: null retains the ordinary action / message presentation.
    val alertSeverity: VaultAlertSeverity? = null,
    // VALUE / URI only: use password character colors, as requested by the producer.
    val colorize: Boolean = false,
    // URI only: resolved artwork and its loading/disabled/failure placeholder.
    val uriIcon: UriIconSnapshot? = null,
)

enum class VaultFilterItemKind {
    /** A collapsible group header (Account, Type, Folder, …). */
    SECTION,

    /** A single toggleable filter. */
    ITEM,
}

/**
 * A flat, Swift-friendly projection of one row of a shared filter tree (vault
 * list / Watchtower / SSH agent); toggle it / expand it via its handler [id].
 */
data class VaultFilterItemSnapshot(
    val id: String,
    val kind: VaultFilterItemKind,
    val sectionId: String,
    val title: String,
    val text: String?,
    val checked: Boolean,
    val enabled: Boolean,
    val expanded: Boolean,
    val indent: Int,
)

enum class VaultSortItemKind {
    SECTION,
    ITEM,
}

/**
 * A flat, Swift-friendly projection of one list sort option (vault list / Send);
 * select it via its handler [id].
 */
data class VaultSortItemSnapshot(
    val id: String,
    val kind: VaultSortItemKind,
    val title: String,
    val checked: Boolean,
)

enum class VaultListItemKind {
    /** A titled group header. */
    SECTION,

    /** A vault item (cipher) row; [VaultListItemSnapshot.secretId] is set. */
    ITEM,

    /** The "no items" placeholder. */
    NO_ITEMS,

    /** The "no suggestions" placeholder. */
    NO_SUGGESTIONS,

    /**
     * A marker for the inline quick-filter chip flow (the saved custom filters),
     * rendered from the snapshot's custom-section `filters` at this row's position.
     */
    QUICK_FILTERS,
}

/**
 * A flat, Swift-friendly projection of one row of the shared vault list. For
 * [VaultListItemKind.ITEM] rows, [secretId] + [accountId] identify the cipher so
 * the detail pane can observe it via [KeyguardCore.observeCipherDetail].
 */
/** One inline badge of a cipher row — an icon plus a title and optional subtitle. */
data class VaultListBadgeSnapshot(
    val title: String,
    val text: String?,
    val iconName: String,
)

internal fun RichBadge.toSnapshot(): VaultListBadgeSnapshot =
    VaultListBadgeSnapshot(title = title, text = text, iconName = iconName)

data class VaultListItemSnapshot(
    val id: String,
    val kind: VaultListItemKind,
    val secretId: String?,
    val accountId: String?,
    val title: String,
    val text: String?,
    val favourite: Boolean,
    /**
     * A concrete, directly-loadable website favicon URL for [VaultListItemKind.ITEM]
     * rows, or `null` when there is no website icon (icon disabled, no site URL, or a
     * non-login type). Resolved by the shared favicon server, honoring the user's
     * "load website icons" setting.
     */
    val iconUrl: String?,
    /** Initials to render when [iconUrl] is `null` / fails to load. */
    val iconPlaceholder: String?,
    val typeName: String? = null,
    val accentArgbLight: Int? = null,
    val accentArgbDark: Int? = null,
    val reprompt: Boolean = false,
    val hasAttachments: Boolean = false,
    val hasError: Boolean = false,
    val isMultiline: Boolean = false,
    val organizationName: String? = null,
    val organizationAccentArgbLight: Int? = null,
    val organizationAccentArgbDark: Int? = null,
    val shapeState: Int = 0,
    val hasChevron: Boolean = false,
    val selected: Boolean = false,
    val selecting: Boolean = false,
    val hasTotp: Boolean = false,
    val passwordBadges: List<VaultListBadgeSnapshot> = emptyList(),
    val passkeyBadges: List<VaultListBadgeSnapshot> = emptyList(),
    val attachmentBadges: List<VaultListBadgeSnapshot> = emptyList(),
    /**
     * The matched-field context badge shown under the row while a search is active
     * (e.g. the matched note / username snippet with its field icon), or `null` when
     * the item is not a search hit. Mirrors the Compose `VaultItemSearchContextBadge`.
     */
    val searchContextBadge: VaultListBadgeSnapshot? = null,
    /**
     * Handler id (routed via [KeyguardCore.invokeEntryAction]) that toggles this row's
     * membership in the active multi-selection — a long-press to begin selecting, or a
     * tap while selecting. Set only on [VaultListItemKind.ITEM] rows of a list that
     * supports selection (the Duplicates screen); `null` everywhere else. Mirrors the
     * Folders row's `toggleActionId`.
     */
    val toggleActionId: String? = null,
)

data class KeyguardCipher(
    val id: String,
    val accountId: String,
    val name: String,
    val username: String?,
)
