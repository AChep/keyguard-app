package com.artemchep.keyguard.apple.model

import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.left
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.formatCardNumber
import com.artemchep.keyguard.feature.home.vault.component.obscureCardNumber
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.totp.TotpCodeState
import com.artemchep.keyguard.ui.totp.totpCodeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus
import org.jetbrains.compose.resources.StringResource

/** One option of a settings picker (dropdown). [id] is an opaque, stable key. */
data class SettingOptionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/**
 * A text field. The edit closure stays Kotlin-side, keyed by [id]; SwiftUI echoes [id] back through the
 * owning screen's `set…` method to reach it. [vlType] / [vlText] carry the optional inline validation
 * banner (severity name + message).
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

/** Also registers the live [onChange] into [handlers] under [id], for the owning screen's `set…(id, text)`. */
internal fun TextFieldModel.toFieldSnapshot(
    handlers: MutableMap<String, (String) -> Unit>,
    id: String = state.id,
    placeholder: String? = hint,
): TextFieldSnapshot {
    onChange?.let { handlers[id] = it }
    return toFieldSnapshot(id = id, placeholder = placeholder)
}

/**
 * Side effect: registers every item action closure into [actionHandlers] under an opaque id that SwiftUI
 * invokes back. [notesText] is the Markdown note body, which the `Note` item only carries as a rendering flag.
 */
internal suspend fun buildVaultItemSnapshots(
    items: List<VaultViewItem>,
    notesText: String?,
    leContext: LeContext,
    actionHandlers: MutableMap<String, () -> Unit>,
    // Keyed by item id; only the cipher detail has TOTP rows.
    totpStates: Map<String, TotpFieldSnapshot> = emptyMap(),
    // The Gravatar URL for the "login.username" row; null unless the Gravatar
    // preference is on.
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
    // a concealed row's value is sent and SwiftUI masks it locally.
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

    suspend fun registerAction(
        itemId: String,
        onClick: (() -> Unit)?,
        title: StringResource,
        suffix: String = "onclick",
    ): VaultActionSnapshot? {
        onClick ?: return null
        val actionId = "$itemId:$suffix"
        actionHandlers[actionId] = onClick
        return VaultActionSnapshot(actionId, textResource(title, leContext), isCopy = false)
    }

    for (item in items) {
        when (item) {
            is VaultViewItem.Value -> {
                // Reveal gating (cipher detail only): a concealed value's plaintext is
                // withheld until the producer reports it revealed. The reveal request
                // goes through `Visibility.transformUserEvent`, so a reprompt fires first.
                // A policy-hidden field (Visibility.hidden) is never revealable.
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
                // SwiftUI has no table row, so flatten it: the optional header becomes a
                // SECTION, then each row is a monospace VALUE (fingerprints / algorithms /
                // key ids read best fixed-width).
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
                // There is no native linked-item row, so a cipher link becomes a tappable
                // ACTION row carrying the target's name / subtitle.
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
                // Mirrors the Compose `VaultViewCardItem` header. The producer emits the
                // expiry / CVV / valid-from rows SEPARATELY as localized VALUE rows, so do
                // NOT re-flatten them here.
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
                    text = card.cardholderName,
                    concealed = numberConcealed,
                    monospace = true,
                    launchUrl = null,
                    switchValue = false,
                    actions = item.dropdown.toActionSnapshots(item.id),
                    shapeState = item.shapeState,
                    // Falls back to the credit-card type, like the Compose item.
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
                // Mirrors the Compose `VaultViewIdentityItem` header. The producer emits
                // every identity field SEPARATELY as a localized VALUE row, so do NOT
                // re-flatten them here.
                val idn = item.data
                val name = listOfNotNull(idn.firstName, idn.middleName, idn.lastName)
                    .joinToString(separator = " ")
                    .ifBlank { null }
                out += VaultItemSnapshot(
                    id = item.id,
                    kind = VaultItemKind.IDENTITY,
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
                out += alertSnapshot(
                    item.id,
                    textResource(Res.string.reused_password, leContext),
                    textResource(
                        Res.plurals.reused_password_items_count_plural,
                        leContext,
                        item.count,
                        item.count,
                    ),
                    registerAction(item.id, item.onClick, Res.string.open_action),
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.ERROR,
                )
            }

            is VaultViewItem.InactiveTotp -> {
                out += alertSnapshot(
                    item.id,
                    textResource(Res.string.twofa_available, leContext),
                    null,
                    registerAction(item.id, item.onClick, Res.string.open_action),
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.WARNING,
                )
            }

            is VaultViewItem.InactivePasskey -> {
                out += alertSnapshot(
                    item.id,
                    textResource(Res.string.passkey_available, leContext),
                    null,
                    registerAction(item.id, item.onClick, Res.string.open_action),
                    shapeState = item.shapeState,
                    severity = VaultAlertSeverity.INFO,
                )
            }

            is VaultViewItem.Passkey -> {
                val useActions = listOfNotNull(
                    registerAction(item.id, item.onUse, Res.string.passkey_use_short, suffix = "use"),
                )
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
                out += alertSnapshot(
                    item.id,
                    item.name,
                    item.message,
                    registerAction(item.id, item.onRetry, Res.string.retry),
                    shapeState = item.shapeState,
                )
            }

            is VaultViewItem.Info -> {
                out += alertSnapshot(item.id, item.name, item.message)
            }

            is VaultViewItem.Planeta -> {
                val title = textResource(Res.string.fingerprint, leContext)
                out += alertSnapshot(item.id, title, item.fingerprint)
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
 * Registers each action's handler into [actionHandlers] under an id scoped by [idPrefix]. Pass a distinct
 * [idPrefix] (e.g. `"header"`) so the ids cannot collide with row ids.
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

/** [action], when present, must already be registered in the caller's action handlers. */
internal fun alertSnapshot(
    id: String,
    title: String,
    message: String?,
    action: VaultActionSnapshot? = null,
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
    actions = listOfNotNull(action),
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

    /** The card header; the expiry, CVV and other card fields arrive as separate VALUE rows. */
    CARD,

    /**
     * The identity header: [VaultItemSnapshot.title] is the honorific (Mr./Mrs./…), [VaultItemSnapshot.text]
     * the full name, and the actions are the quick-action buttons. Each field arrives as a separate VALUE row.
     */
    IDENTITY,
}

/** A menu / button action; [id] routes back through the owning screen's `invoke…` method. */
data class VaultActionSnapshot(
    val id: String,
    val title: String,
    val isCopy: Boolean,
    /** SF Symbol name of the icon; `null` when the shared icon has no SF Symbol mapping (most menu actions). */
    val iconName: String? = null,
    /** `true` when this action opens a new group in an overflow menu; SwiftUI draws a divider before it. */
    val startsSection: Boolean = false,
    /** The on/off state of a toggle-backed overflow action, rendered as a checkmark; `null` for plain actions. */
    val switchState: Boolean? = null,
    /** `true` for a destructive action, so SwiftUI can give it the destructive role. */
    val danger: Boolean = false,
)

/**
 * Only the icons shown on labelled buttons (the identity quick actions) are mapped; for anything else
 * SwiftUI falls back to a text-only button.
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
 * Sampled from the shared [AttachmentItem] each time the snapshot rebuilds. [progress] is the downloaded
 * fraction (0..1), or -1 when indeterminate.
 */
data class AttachmentFieldSnapshot(
    val status: AttachmentStatusKind,
    val progress: Float,
    val downloadedText: String?,
    val autoResume: Boolean,
)

/**
 * The live state of a TOTP badge, computed by the shared [totpCodeFlow] and pushed every second, so the
 * Swift view only renders / animates it.
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

/** Starts with [TotpFieldSnapshot.loading] so downstream combines can emit before the first code arrives. */
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
 * One row of the vault item detail; the populated fields depend on [kind]. [text] is the primary value,
 * masked in the UI when [concealed].
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
    // NOTE only: render [text] as Markdown (the preference is on and the body parses).
    val markdown: Boolean = false,
    // VALUE only: the username row's Gravatar; null when the preference is off.
    val avatarUrl: String? = null,
    /** A ShapeState bitmask (START/END/CENTER/ALL) from the producer's transformShapes(); -1 = not grouped. */
    val shapeState: Int = -1,
    /** The row's own tap action; null when the row itself is not tappable. */
    val clickActionId: String? = null,
    val badges: List<VaultBadgeSnapshot> = emptyList(),
    val attachment: AttachmentFieldSnapshot? = null,
    /** SPACER height in points. */
    val spacerHeight: Float = 0f,
    /**
     * CARD only: the brand label drawn above the number; null when unknown. [text] holds the
     * cardholder name and the number's reveal toggle reuses [concealed].
     */
    val cardBrand: String? = null,
    /** CARD only: the number with group spaces; null while the plaintext is withheld (see [isVisible]). */
    val cardNumberFormatted: String? = null,
    /** CARD only: the number with leading digits replaced with •; always safe to send. */
    val cardNumberObscured: String? = null,
    /**
     * VALUE / CARD reveal gating (mirrors the Compose per-field Visibility state): whether the producer
     * reports the concealed field as revealed. While a concealed field is NOT visible, the bridge withholds
     * the plaintext entirely ([text] / [cardNumberFormatted] are null), so SwiftUI cannot leak it by flipping
     * a local @State. Always `true` for non-concealed rows.
     */
    val isVisible: Boolean = true,
    /**
     * VALUE / CARD only: the action id the eye toggle invokes to REQUEST a reveal. The handler runs the
     * shared producer's `Visibility.transformUserEvent`, i.e. the SAME `executeWithRePrompt` path the copy
     * action uses — so on a master-password-reprompt cipher the elevated-access dialog fires FIRST, and only
     * on success does the producer re-emit the snapshot with the value + [isVisible] = true. `null` for
     * non-concealable rows; invoking it never discloses anything by itself.
     */
    val revealActionId: String? = null,
    /**
     * VALUE / CARD only: the field is concealed by an org "hide passwords" policy (Visibility.hidden), so
     * the secret can NEVER be revealed (not even behind a reprompt), exactly as Compose renders no reveal
     * toggle for it. When true the bridge withholds the plaintext ([text] / [cardNumberFormatted] are null),
     * [isVisible] is false, [revealActionId] is null, and SwiftUI must render the masked value with NO eye
     * toggle (and must NOT fall back to a local @State reveal). Distinguishes a cipher-detail policy-masked
     * field from a Send/Account concealed field, which keeps the local reveal toggle.
     */
    val revealLocked: Boolean = false,
    /** ALERT only; null keeps the ordinary action / message presentation. */
    val alertSeverity: VaultAlertSeverity? = null,
    // VALUE / URI only: use password character colors.
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

/** One row of a shared filter tree; toggle or expand it via its handler [id]. */
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

/** One list sort option; select it via its handler [id]. */
data class VaultSortItemSnapshot(
    val id: String,
    val kind: VaultSortItemKind,
    val title: String,
    val checked: Boolean,
)

enum class VaultListItemKind {
    /** A titled group header. */
    SECTION,

    /** An item row; the row's `secretId` is set. */
    ITEM,

    /** The "no items" placeholder. */
    NO_ITEMS,
}
