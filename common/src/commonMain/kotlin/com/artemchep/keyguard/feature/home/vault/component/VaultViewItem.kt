package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem,
) = when (item) {
    is VaultViewItem.Card -> VaultViewCardItem(modifier, item)
    is VaultViewItem.Folder -> VaultViewFolderItem(modifier, item)
    is VaultViewItem.Organization -> VaultViewOrganizationItem(modifier, item)
    is VaultViewItem.Collection -> VaultViewCollectionItem(modifier, item)
    is VaultViewItem.Error -> VaultViewErrorItem(modifier, item)
    is VaultViewItem.Info -> VaultViewInfoItem(modifier, item)
    is VaultViewItem.Identity -> VaultViewIdentityItem(modifier, item)
    is VaultViewItem.QuickActions -> VaultViewQuickActionsItem(modifier, item)
    is VaultViewItem.Action -> VaultViewActionItem(modifier, item)
    is VaultViewItem.Value -> VaultViewValueItem(modifier, item)
    is VaultViewItem.Switch -> VaultViewSwitchItem(modifier, item)
    is VaultViewItem.Uri -> VaultViewUriItem(modifier, item)
    is VaultViewItem.Button -> VaultViewButtonItem(modifier, item)
    is VaultViewItem.Label -> VaultViewLabelItem(modifier, item)
    is VaultViewItem.Spacer -> VaultViewSpacerItem(modifier, item)
    is VaultViewItem.Note -> VaultViewNoteItem(modifier, item)
    is VaultViewItem.Totp -> VaultViewTotpItem(modifier, item)
    is VaultViewItem.Passkey -> VaultViewPasskeyItem(modifier, item)
    is VaultViewItem.ReusedPassword -> VaultViewReusedPasswordItem(modifier, item)
    is VaultViewItem.InactiveTotp -> VaultViewInactiveTotpItem(modifier, item)
    is VaultViewItem.InactivePasskey -> VaultViewInactivePasskeyItem(modifier, item)
    is VaultViewItem.Section -> VaultViewSectionItem(modifier, item)
    is VaultViewItem.Attachment -> VaultViewAttachmentItem(modifier, item)
}
