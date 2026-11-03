package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.filepicker.FileDropOverlay
import com.artemchep.keyguard.feature.filepicker.FileDropTargetBox
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItem
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.client_certificate
import com.artemchep.keyguard.res.client_certificate_details
import com.artemchep.keyguard.res.client_certificate_drop_here
import com.artemchep.keyguard.res.client_certificate_file_types
import com.artemchep.keyguard.res.client_certificate_note
import com.artemchep.keyguard.res.client_certificate_remove
import com.artemchep.keyguard.res.client_certificate_remove_note
import com.artemchep.keyguard.res.client_certificate_remove_title
import com.artemchep.keyguard.res.client_certificate_replace
import com.artemchep.keyguard.res.save
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.danger
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountClientCertificateScreen(accountId: String) {
    val state = produceAccountClientCertificateState(accountId).getOrNull()
    if (state != null) FilePickerEffect(state.filePickerIntents)
    AccountClientCertificateContent(state)
}

/** Stateless screen body; `null` while the certificate is loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountClientCertificateContent(state: AccountClientCertificateState?) {
    val onFileDrop by rememberUpdatedState(state?.onFileDrop)
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = { Text(stringResource(Res.string.client_certificate)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
        // Saving only applies to a replacement, so the button
        // appears once there is something to save.
        floatingActionState = rememberUpdatedState(
            state?.takeIf { it.unsaved }?.let { FabState(onClick = it.onSave, model = null) },
        ),
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Crossfade(
                        modifier = Modifier.size(24.dp),
                        targetState = state?.busy == true,
                    ) { busy ->
                        if (busy) KeyguardLoadingIndicator()
                        else Icon(Icons.Outlined.Save, null)
                    }
                },
                text = { Text(stringResource(Res.string.save)) },
            )
        },
        overlay = {
            DefaultProgressBar(visible = state == null || state.busy)
            FileDropTargetBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding.value),
                enabled = onFileDrop != null,
                onFileDrop = { file -> onFileDrop?.invoke(file) },
                overlay = {
                    FileDropOverlay(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(horizontal = Dimens.contentPadding),
                        text = stringResource(Res.string.client_certificate_drop_here),
                    )
                },
            )
        },
    ) {
        if (state != null) certificateItems(state)
    }
    if (state?.confirmRemoval == true) RemovalDialog(state)
}

private fun LazyListScope.certificateItems(state: AccountClientCertificateState) {
    state.note?.let { note ->
        item("note") {
            FlatSimpleNote(
                modifier = Modifier.animateItem(),
                note = note,
            )
        }
    }
    val certificate = state.certificate
    if (certificate == null) {
        item("empty") {
            ClientCertificateEmptyCard(
                modifier = Modifier.animateItem(),
                onAdd = state.onImport,
            )
        }
    } else {
        configuredCertificateItems(state, certificate)
    }
    item("footer") {
        ClientCertificateNote(
            modifier = Modifier.animateItem(),
        )
    }
}

private fun LazyListScope.configuredCertificateItems(
    state: AccountClientCertificateState,
    certificate: AccountClientCertificateState.Certificate,
) {
    item("header") {
        ClientCertificateHeader(
            modifier = Modifier.animateItem(),
            certificate = certificate,
            unsaved = state.unsaved,
        )
    }
    item("replace") {
        FlatItemSimpleExpressive(
            modifier = Modifier.animateItem(),
            shapeState = ShapeState.CENTER,
            leading = icon<RowScope>(Icons.Outlined.FileOpen),
            title = { Text(stringResource(Res.string.client_certificate_replace)) },
            text = { Text(stringResource(Res.string.client_certificate_file_types)) },
            onClick = state.onImport,
        )
    }
    item("remove") {
        FlatItemSimpleExpressive(
            modifier = Modifier.animateItem(),
            shapeState = ShapeState.END,
            contentColor = MaterialTheme.colorScheme.danger,
            leading = icon<RowScope>(Icons.Outlined.DeleteOutline),
            title = { Text(stringResource(Res.string.client_certificate_remove)) },
            onClick = state.onRemove,
        )
    }
    if (certificate.details.isNotEmpty()) {
        item("details.section") {
            Section(
                modifier = Modifier.animateItem(),
                text = stringResource(Res.string.client_certificate_details),
            )
        }
        items(certificate.details, key = { it.id }) { item ->
            VaultViewItem(
                modifier = Modifier.animateItem(),
                item = item,
            )
        }
    }
}

@Composable
private fun ClientCertificateNote(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.client_certificate_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun RemovalDialog(state: AccountClientCertificateState) {
    AlertDialog(
        onDismissRequest = state.onDismissRemoval,
        icon = { Icon(Icons.Outlined.DeleteOutline, null) },
        title = { Text(stringResource(Res.string.client_certificate_remove_title)) },
        text = { Text(stringResource(Res.string.client_certificate_remove_note)) },
        confirmButton = {
            TextButton(onClick = state.onConfirmRemoval) {
                Text(stringResource(Res.string.client_certificate_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = state.onDismissRemoval) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
