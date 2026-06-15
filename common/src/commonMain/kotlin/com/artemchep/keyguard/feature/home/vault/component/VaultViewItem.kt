package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem,
) = VaultViewItemContent(
    modifier = modifier,
    item = item,
    renderers = commonVaultViewItemRenderers,
)

private val commonVaultViewItemRenderers = VaultViewItemRenderers(
    card = { modifier, item -> VaultViewCardItem(modifier, item) },
    folder = { modifier, item -> VaultViewFolderItem(modifier, item) },
    tags = { modifier, item -> VaultViewTagsItem(modifier, item) },
    organization = { modifier, item -> VaultViewOrganizationItem(modifier, item) },
    collection = { modifier, item -> VaultViewCollectionItem(modifier, item) },
    error = { modifier, item -> VaultViewErrorItem(modifier, item) },
    info = { modifier, item -> VaultViewInfoItem(modifier, item) },
    identity = { modifier, item -> VaultViewIdentityItem(modifier, item) },
    quickActions = { modifier, item -> VaultViewQuickActionsItem(modifier, item) },
    quickBadges = { modifier, item -> VaultViewQuickBadgesItem(modifier, item) },
    action = { modifier, item -> VaultViewActionItem(modifier, item) },
    value = { modifier, item -> VaultViewValueItem(modifier, item) },
    switch = { modifier, item -> VaultViewSwitchItem(modifier, item) },
    uri = { modifier, item -> VaultViewUriItem(modifier, item) },
    button = { modifier, item -> VaultViewButtonItem(modifier, item) },
    planeta = { modifier, item -> VaultViewPlanetaItem(modifier, item) },
    label = { modifier, item -> VaultViewLabelItem(modifier, item) },
    spacer = { modifier, item -> VaultViewSpacerItem(modifier, item) },
    note = { modifier, item -> VaultViewNoteItem(modifier, item) },
    totp = { modifier, item -> VaultViewTotpItem(modifier, item) },
    passkey = { modifier, item -> VaultViewPasskeyItem(modifier, item) },
    reusedPassword = { modifier, item -> VaultViewReusedPasswordItem(modifier, item) },
    qr = { modifier, item -> VaultViewQrItem(modifier, item) },
    inactiveTotp = { modifier, item -> VaultViewInactiveTotpItem(modifier, item) },
    inactivePasskey = { modifier, item -> VaultViewInactivePasskeyItem(modifier, item) },
    section = { modifier, item -> VaultViewSectionItem(modifier, item) },
    attachment = { modifier, item -> VaultViewAttachmentItem(modifier, item) },
)
