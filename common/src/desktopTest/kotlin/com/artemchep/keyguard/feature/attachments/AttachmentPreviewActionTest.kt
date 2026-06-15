package com.artemchep.keyguard.feature.attachments

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactoryDefault
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttachmentPreviewActionTest {
    @Test
    fun `preview action appears before download for not downloaded previewable attachments`() {
        val route = route()
        val navigations = mutableListOf<NavigationIntent>()

        val actions = foo(
            translatorScope = TestTranslatorScope,
            vaultViewRouteFactory = VaultViewRouteFactoryDefault,
            fileName = "preview.png",
            status = FooStatus.None,
            launchViewCipherData = null,
            previewRoute = route,
            downloadIo = {},
            removeIo = {},
            navigate = navigations::add,
        ).actions()

        assertEquals(2, actions.size)
        assertEquals(FlatItemAction.Type.VIEW, actions[0].type)
        assertEquals(FlatItemAction.Type.DOWNLOAD, actions[1].type)

        actions[0].onClick?.invoke()
        val intent = assertIs<NavigationIntent.NavigateToRoute>(navigations.single())
        assertEquals(route, intent.route)
    }

    @Test
    fun `preview action appears before external open for already downloaded previewable attachments`() {
        val route = route()
        val navigations = mutableListOf<NavigationIntent>()

        val actions = foo(
            translatorScope = TestTranslatorScope,
            vaultViewRouteFactory = VaultViewRouteFactoryDefault,
            fileName = "preview.txt",
            status = FooStatus.Downloaded(localUrl = "file:///tmp/preview.txt"),
            launchViewCipherData = null,
            previewRoute = route,
            downloadIo = {},
            removeIo = {},
            navigate = navigations::add,
        ).actions()

        assertEquals(FlatItemAction.Type.VIEW, actions[0].type)
        actions[0].onClick?.invoke()
        val intent = assertIs<NavigationIntent.NavigateToRoute>(navigations.single())
        assertEquals(route, intent.route)
    }

    private fun route() = AttachmentPreviewRoute(
        args = AttachmentPreviewRoute.Args(
            localCipherId = "local-cipher",
            remoteCipherId = "remote-cipher",
            attachmentId = "attachment",
            fileName = "preview.png",
        ),
    )

    private fun List<ContextItem>.actions() = filterIsInstance<FlatItemAction>()
}

private object TestTranslatorScope : TranslatorScope {
    override suspend fun translate(res: StringResource): String = ""

    override suspend fun translate(res: StringResource, vararg args: Any): String = ""

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = ""
}
