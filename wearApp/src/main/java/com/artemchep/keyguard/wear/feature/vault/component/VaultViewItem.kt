package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItemContent
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItemRenderers
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun WearVaultViewItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem,
    transformation: SurfaceTransformation? = null,
) = VaultViewItemContent(
    modifier = modifier,
    item = item,
    renderers = wearVaultViewItemRenderers(transformation),
)

private fun wearVaultViewItemRenderers(
    transformation: SurfaceTransformation?,
) = VaultViewItemRenderers(
    card = { modifier, item -> WearVaultViewCardItem(modifier, item, transformation = transformation) },
    folder = { modifier, item -> WearVaultViewFolderItem(modifier, item, transformation = transformation) },
    tags = { modifier, item -> WearVaultViewTagsItem(modifier, item, transformation = transformation) },
    organization = { modifier, item -> WearVaultViewOrganizationItem(modifier, item, transformation = transformation) },
    collection = { modifier, item -> WearVaultViewCollectionItem(modifier, item, transformation = transformation) },
    error = { modifier, item -> WearVaultViewErrorItem(modifier, item, transformation = transformation) },
    info = { modifier, item -> WearVaultViewInfoItem(modifier, item, transformation = transformation) },
    identity = { modifier, item -> WearVaultViewIdentityItem(modifier, item, transformation = transformation) },
    quickActions = { modifier, item -> WearVaultViewQuickActionsItem(modifier, item, transformation = transformation) },
    quickBadges = { modifier, item -> WearVaultViewQuickBadgesItem(modifier, item, transformation = transformation) },
    action = { modifier, item -> WearVaultViewActionItem(modifier, item, transformation = transformation) },
    value = { modifier, item -> WearVaultViewValueItem(modifier, item, transformation = transformation) },
    switch = { modifier, item -> WearVaultViewSwitchItem(modifier, item, transformation = transformation) },
    uri = { modifier, item -> WearVaultViewUriItem(modifier, item, transformation = transformation) },
    button = { modifier, item -> WearVaultViewButtonItem(modifier, item, transformation = transformation) },
    label = { modifier, item -> WearVaultViewLabelItem(modifier, item, transformation = transformation) },
    spacer = { modifier, item -> WearVaultViewSpacerItem(modifier, item, transformation = transformation) },
    note = { modifier, item -> WearVaultViewNoteItem(modifier, item, transformation = transformation) },
    totp = { modifier, item -> WearVaultViewTotpItem(modifier, item, transformation = transformation) },
    passkey = { modifier, item -> WearVaultViewPasskeyItem(modifier, item, transformation = transformation) },
    reusedPassword = { modifier, item -> WearVaultViewReusedPasswordItem(modifier, item, transformation = transformation) },
    qr = { modifier, item -> WearVaultViewQrItem(modifier, item, transformation = transformation) },
    inactiveTotp = { modifier, item -> WearVaultViewInactiveTotpItem(modifier, item, transformation = transformation) },
    inactivePasskey = { modifier, item -> WearVaultViewInactivePasskeyItem(modifier, item, transformation = transformation) },
    section = { modifier, item -> WearVaultViewSectionItem(modifier, item, transformation = transformation) },
    attachment = { modifier, item -> WearVaultViewAttachmentItem(modifier, item, transformation = transformation) },
)
