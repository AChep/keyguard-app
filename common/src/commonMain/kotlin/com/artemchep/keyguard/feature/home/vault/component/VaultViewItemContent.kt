package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Immutable
data class VaultViewItemRenderers(
    val card: @Composable (Modifier, VaultViewItem.Card) -> Unit,
    val folder: @Composable (Modifier, VaultViewItem.Folder) -> Unit,
    val tags: @Composable (Modifier, VaultViewItem.Tags) -> Unit,
    val organization: @Composable (Modifier, VaultViewItem.Organization) -> Unit,
    val collection: @Composable (Modifier, VaultViewItem.Collection) -> Unit,
    val error: @Composable (Modifier, VaultViewItem.Error) -> Unit,
    val info: @Composable (Modifier, VaultViewItem.Info) -> Unit,
    val identity: @Composable (Modifier, VaultViewItem.Identity) -> Unit,
    val quickActions: @Composable (Modifier, VaultViewItem.QuickActions) -> Unit,
    val quickBadges: @Composable (Modifier, VaultViewItem.QuickBadges) -> Unit,
    val action: @Composable (Modifier, VaultViewItem.Action) -> Unit,
    val value: @Composable (Modifier, VaultViewItem.Value) -> Unit,
    val switch: @Composable (Modifier, VaultViewItem.Switch) -> Unit,
    val uri: @Composable (Modifier, VaultViewItem.Uri) -> Unit,
    val button: @Composable (Modifier, VaultViewItem.Button) -> Unit,
    val label: @Composable (Modifier, VaultViewItem.Label) -> Unit,
    val spacer: @Composable (Modifier, VaultViewItem.Spacer) -> Unit,
    val note: @Composable (Modifier, VaultViewItem.Note) -> Unit,
    val totp: @Composable (Modifier, VaultViewItem.Totp) -> Unit,
    val passkey: @Composable (Modifier, VaultViewItem.Passkey) -> Unit,
    val reusedPassword: @Composable (Modifier, VaultViewItem.ReusedPassword) -> Unit,
    val qr: @Composable (Modifier, VaultViewItem.Qr) -> Unit,
    val inactiveTotp: @Composable (Modifier, VaultViewItem.InactiveTotp) -> Unit,
    val inactivePasskey: @Composable (Modifier, VaultViewItem.InactivePasskey) -> Unit,
    val section: @Composable (Modifier, VaultViewItem.Section) -> Unit,
    val attachment: @Composable (Modifier, VaultViewItem.Attachment) -> Unit,
)

@Composable
fun VaultViewItemContent(
    modifier: Modifier = Modifier,
    item: VaultViewItem,
    renderers: VaultViewItemRenderers,
) = when (item) {
    is VaultViewItem.Card -> renderers.card(modifier, item)
    is VaultViewItem.Folder -> renderers.folder(modifier, item)
    is VaultViewItem.Tags -> renderers.tags(modifier, item)
    is VaultViewItem.Organization -> renderers.organization(modifier, item)
    is VaultViewItem.Collection -> renderers.collection(modifier, item)
    is VaultViewItem.Error -> renderers.error(modifier, item)
    is VaultViewItem.Info -> renderers.info(modifier, item)
    is VaultViewItem.Identity -> renderers.identity(modifier, item)
    is VaultViewItem.QuickActions -> renderers.quickActions(modifier, item)
    is VaultViewItem.QuickBadges -> renderers.quickBadges(modifier, item)
    is VaultViewItem.Action -> renderers.action(modifier, item)
    is VaultViewItem.Value -> renderers.value(modifier, item)
    is VaultViewItem.Switch -> renderers.switch(modifier, item)
    is VaultViewItem.Uri -> renderers.uri(modifier, item)
    is VaultViewItem.Button -> renderers.button(modifier, item)
    is VaultViewItem.Label -> renderers.label(modifier, item)
    is VaultViewItem.Spacer -> renderers.spacer(modifier, item)
    is VaultViewItem.Note -> renderers.note(modifier, item)
    is VaultViewItem.Totp -> renderers.totp(modifier, item)
    is VaultViewItem.Passkey -> renderers.passkey(modifier, item)
    is VaultViewItem.ReusedPassword -> renderers.reusedPassword(modifier, item)
    is VaultViewItem.Qr -> renderers.qr(modifier, item)
    is VaultViewItem.InactiveTotp -> renderers.inactiveTotp(modifier, item)
    is VaultViewItem.InactivePasskey -> renderers.inactivePasskey(modifier, item)
    is VaultViewItem.Section -> renderers.section(modifier, item)
    is VaultViewItem.Attachment -> renderers.attachment(modifier, item)
}
