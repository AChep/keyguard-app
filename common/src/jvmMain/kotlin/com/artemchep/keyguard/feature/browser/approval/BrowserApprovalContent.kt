package com.artemchep.keyguard.feature.browser.approval

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.common.service.browser.BrowserRelease
import com.artemchep.keyguard.feature.agent.AgentApprovalChoiceList
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.settings.component.agentApprovalWindowTitle
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_approval_add_uri
import com.artemchep.keyguard.res.browser_extension_approval_always_ask_note
import com.artemchep.keyguard.res.browser_extension_approval_fill_message
import com.artemchep.keyguard.res.browser_extension_approval_origin_note
import com.artemchep.keyguard.res.browser_extension_approval_pair_code_note
import com.artemchep.keyguard.res.browser_extension_approval_pair_message_known_app
import com.artemchep.keyguard.res.browser_extension_approval_pair_message_unknown_app
import com.artemchep.keyguard.res.browser_extension_approval_pair_name_label
import com.artemchep.keyguard.res.browser_extension_approval_pair_title
import com.artemchep.keyguard.res.browser_extension_approval_passkey_create_message
import com.artemchep.keyguard.res.browser_extension_approval_passkey_get_message
import com.artemchep.keyguard.res.browser_extension_approval_remember_note
import com.artemchep.keyguard.res.browser_extension_approval_reprompt_note
import com.artemchep.keyguard.res.browser_extension_approval_rp_id_note
import com.artemchep.keyguard.res.browser_extension_approval_title
import com.artemchep.keyguard.res.browser_extension_approval_top_site_note
import com.artemchep.keyguard.res.browser_extension_approval_totp_message
import com.artemchep.keyguard.res.browser_extension_approval_unmatched_note
import com.artemchep.keyguard.res.browser_extension_approval_user_verification_note
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlin.time.Duration
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content of an approval window of a browser extension, styled
 * like the other agent approval windows: pairing a browser, or releasing a
 * login or a one-time password to a page.
 *
 * @param request The pending approval request; not an unlock request, nor
 *   a pick of a passkey, see [BrowserPasskeyPickContent].
 * @param name The name of the browser, for a pairing request.
 * @param addUri Whether approving adds the page to the item, for a release
 *   that offers it, see [BrowserRelease.addUri].
 * @param busy `true` while the approval is being verified.
 */
@Composable
fun BrowserApprovalContent(
    request: BrowserApprovalRequest,
    name: String,
    onNameChange: (String) -> Unit,
    addUri: Boolean,
    onAddUriChange: (Boolean) -> Unit,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    val operation = request.operation
    BrowserApprovalDialog(
        request = request,
        icon = if (operation is BrowserOperation.Pair) Icons.Outlined.Devices else Icons.Outlined.Language,
        title = if (operation is BrowserOperation.Pair) {
            stringResource(Res.string.browser_extension_approval_pair_title)
        } else {
            stringResource(Res.string.browser_extension_approval_title)
        },
        message = { who -> browserApprovalMessage(operation, who = who) },
        confirmEnabled = !busy && (operation !is BrowserOperation.Pair || name.isNotBlank()),
        onDeny = onDeny,
        onAllow = onAllow,
    ) {
        when (operation) {
            is BrowserOperation.Pair -> BrowserPairContent(
                operation = operation,
                name = name,
                onNameChange = onNameChange.takeUnless { busy },
            )

            is BrowserOperation.Release -> BrowserReleaseContent(
                release = operation.release,
                icon = operation.presentation().first,
                addUri = addUri,
                onAddUriChange = onAddUriChange.takeUnless { busy },
            )

            // Rendered by BrowserPasskeyPickContent; an unlock
            // approves itself once the vault unlocks.
            is BrowserOperation.PasskeyPick,
            BrowserOperation.Unlock,
            -> Unit
        }
    }
}

/** The icon of the item that the operation releases, and the message of its prompt. */
private fun BrowserOperation.Release.presentation(): Pair<ImageVector, StringResource> = when (this) {
    is BrowserOperation.Fill -> Icons.Outlined.Password to Res.string.browser_extension_approval_fill_message
    is BrowserOperation.Totp -> Icons.Outlined.Pin to Res.string.browser_extension_approval_totp_message
    is BrowserOperation.PasskeyGet -> Icons.Outlined.Key to Res.string.browser_extension_approval_passkey_get_message
    is BrowserOperation.PasskeyCreate ->
        Icons.Outlined.Key to Res.string.browser_extension_approval_passkey_create_message
}

/** What the browser asks for, naming it [who]. */
@Composable
internal fun browserApprovalMessage(
    operation: BrowserOperation,
    who: String?,
): AnnotatedString = when (operation) {
    is BrowserOperation.Release -> releaseMessage(
        message = operation.presentation().second,
        who = who,
        release = operation.release,
    )

    // The site is the same for all passkeys.
    is BrowserOperation.PasskeyPick -> releaseMessage(
        message = Res.string.browser_extension_approval_passkey_get_message,
        who = who,
        release = operation.choices.first().release,
    )

    is BrowserOperation.Pair,
    BrowserOperation.Unlock,
    -> if (who != null) {
        annotatedResource(
            Res.string.browser_extension_approval_pair_message_known_app,
            who to SpanStyle(
                fontWeight = FontWeight.Bold,
            ),
        )
    } else {
        annotatedResource(
            Res.string.browser_extension_approval_pair_message_unknown_app,
        )
    }
}

@Composable
private fun releaseMessage(
    message: StringResource,
    who: String?,
    release: BrowserRelease,
) = annotatedResource(
    message,
    who.orEmpty() to SpanStyle(
        fontWeight = FontWeight.Bold,
    ),
    release.site.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH).orEmpty() to SpanStyle(
        fontWeight = FontWeight.Bold,
    ),
)

/** The code to compare with the browser's, and the name of the browser. */
@Composable
private fun BrowserPairContent(
    operation: BrowserOperation.Pair,
    name: String,
    onNameChange: ((String) -> Unit)?,
) {
    // Grouped in threes, like the browser shows it.
    val code = remember(operation.code) {
        operation.code.chunked(3).joinToString(separator = " ")
    }
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = code,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.displaySmall,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    BrowserApprovalNote(
        text = stringResource(Res.string.browser_extension_approval_pair_code_note),
    )
    Spacer(modifier = Modifier.height(12.dp))
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = 8.dp),
        label = stringResource(Res.string.browser_extension_approval_pair_name_label),
        value = TextFieldModel(
            text = name,
            onChange = onNameChange,
        ),
        singleLine = true,
    )
}

/** Which page asks, and whose passkey, when the site alone doesn't tell it. */
@Composable
internal fun BrowserReleasePageNotes(
    release: BrowserRelease,
) {
    // A frame of another site's page, which always asks: a warning, as for an unmatched item.
    val topSite = release.topSite
        ?.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    if (topSite != null) {
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.browser_extension_approval_top_site_note, topSite),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    // A page of another host than the site, such as a subdomain.
    val origin = release.origin
        .takeUnless { it.equals(release.site, ignoreCase = true) }
        ?.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    if (origin != null) {
        BrowserApprovalNote(
            text = stringResource(Res.string.browser_extension_approval_origin_note, origin),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    // A passkey of another site, which lists the page among its related origins.
    val rpId = release.otherSiteRpId
        ?.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    if (rpId != null) {
        BrowserApprovalNote(
            text = stringResource(Res.string.browser_extension_approval_rp_id_note, rpId),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** The item, its username and account, and what approving allows. */
@Composable
private fun ColumnScope.BrowserReleaseContent(
    release: BrowserRelease,
    icon: ImageVector,
    addUri: Boolean,
    onAddUriChange: ((Boolean) -> Unit)?,
) {
    val item = remember(release) { release.toChoiceItem() }
    AgentApprovalChoiceList(
        items = listOf(item),
        selected = 0,
        onSelect = null,
        icon = icon,
    )
    Spacer(modifier = Modifier.height(12.dp))
    // Found by search: no URI of the item matches the page.
    if (!release.matched) {
        val site = release.site.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH).orEmpty()
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.browser_extension_approval_unmatched_note, site),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    BrowserReleasePageNotes(release)
    BrowserReleaseNote(release)
    val uri = release.addUri
        ?.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    if (uri != null) {
        Spacer(modifier = Modifier.height(8.dp))
        BrowserAddUriItem(
            uri = uri,
            checked = addUri,
            onCheckedChange = onAddUriChange,
        )
    }
}

/** Whether approving adds the page's [uri] to the item. */
@Composable
private fun BrowserAddUriItem(
    uri: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemLayoutExpressive(
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = onCheckedChange != null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.browser_extension_approval_add_uri, uri),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onCheckedChange?.let { onChange ->
            { onChange(!checked) }
        },
    )
}

/** How long Keyguard remembers approving this item on this site. */
@Composable
internal fun BrowserReleaseNote(
    release: BrowserRelease,
) {
    val context = LocalLeContext
    val window = release.approvalWindow
    val windowTitle by produceState<String?>(null, window) {
        value = agentApprovalWindowTitle(window, context)
    }
    val text = when {
        release.reprompt -> stringResource(Res.string.browser_extension_approval_reprompt_note)
        release.userVerification -> stringResource(Res.string.browser_extension_approval_user_verification_note)
        window <= Duration.ZERO && window != AGENT_APPROVAL_WINDOW_NEVER_ASK ->
            stringResource(Res.string.browser_extension_approval_always_ask_note)

        else -> windowTitle
            ?.let { stringResource(Res.string.browser_extension_approval_remember_note, it) }
    }
    if (text != null) {
        BrowserApprovalNote(text = text)
    }
}

@Composable
private fun BrowserApprovalNote(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}
