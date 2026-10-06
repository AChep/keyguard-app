package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.attachments.AttachmentsRoute
import com.artemchep.keyguard.feature.duplicates.DuplicatesRoute
import com.artemchep.keyguard.feature.equivalentdomains.EquivalentDomainsRoute
import com.artemchep.keyguard.feature.export.ExportRoute
import com.artemchep.keyguard.feature.feedback.FeedbackRoute
import com.artemchep.keyguard.feature.filter.CipherFiltersRoute
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.emailrelay.EmailRelayListRoute
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryRoute
import com.artemchep.keyguard.feature.generator.wordlist.list.WordlistListRoute
import com.artemchep.keyguard.feature.generator.wordlist.view.WordlistViewRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultListRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewPasswordHistoryRoute
import com.artemchep.keyguard.feature.sshagent.history.SshAgentHistoryRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.justdeleteme.directory.JustDeleteMeServicesRoute
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataServicesRoute
import com.artemchep.keyguard.feature.passkeys.directory.PasskeysServicesRoute
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsRoute
import com.artemchep.keyguard.feature.home.settings.SettingsRoute
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.search.SendSort
import com.artemchep.keyguard.feature.send.view.SendViewRoute
import com.artemchep.keyguard.feature.tfa.directory.TwoFaServicesRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.feature.watchtower.alerts.WatchtowerAlertsRoute

/** Reconstructs a [Route] from its data-only [RouteDescriptor]. */
fun RouteDescriptor.toRoute(): Route? = when (this) {
    is RouteDescriptor.VaultCipherView ->
        VaultViewRoute(itemId = itemId, accountId = accountId)

    is RouteDescriptor.VaultList -> {
        val args = VaultRoute.Args(
            appBar = title?.let { VaultRoute.Args.AppBar(title = it) },
            filter = filter,
            sort = sortId?.let { Sort.valueOf(it) },
            main = main,
            searchBy = runCatching { VaultRoute.Args.SearchBy.valueOf(searchBy) }
                .getOrDefault(VaultRoute.Args.SearchBy.ALL),
            trash = trash,
            archive = archive,
            preselect = preselect,
            canAddSecrets = canAddSecrets,
        )
        if (stacked) VaultListRoute(args) else VaultRoute(args)
    }

    is RouteDescriptor.SendView ->
        SendViewRoute(sendId = sendId, accountId = accountId)

    is RouteDescriptor.PasswordHistory ->
        VaultViewPasswordHistoryRoute(itemId = itemId)

    is RouteDescriptor.SshAgentHistory ->
        SshAgentHistoryRoute(cipherId = cipherId)

    is RouteDescriptor.WordlistView ->
        WordlistViewRoute(WordlistViewRoute.Args(wordlistId = wordlistId))

    is RouteDescriptor.Organizations ->
        OrganizationsRoute(OrganizationsRoute.Args(accountId = AccountId(accountId)))

    is RouteDescriptor.Collections ->
        CollectionsRoute(
            CollectionsRoute.Args(accountId = AccountId(accountId), organizationId = organizationId),
        )

    is RouteDescriptor.EquivalentDomains ->
        EquivalentDomainsRoute(EquivalentDomainsRoute.Args(accountId = AccountId(accountId)))

    is RouteDescriptor.Folders ->
        FoldersRoute(FoldersRoute.Args(filter = filter, empty = empty))

    is RouteDescriptor.Duplicates ->
        DuplicatesRoute(DuplicatesRoute.Args(filter = filter))

    is RouteDescriptor.Export ->
        ExportRoute(ExportRoute.Args(title = title, filter = filter))

    is RouteDescriptor.SendList -> {
        val args = SendRoute.Args(
            appBar = title?.let { SendRoute.Args.AppBar(title = it) },
            filter = filter,
            sort = sortId?.let { SendSort.valueOf(it) },
            main = main,
            searchBy = runCatching { SendRoute.Args.SearchBy.valueOf(searchBy) }
                .getOrDefault(SendRoute.Args.SearchBy.ALL),
            trash = trash,
            preselect = preselect,
            canAddSecrets = canAddSecrets,
        )
        SendRoute(args)
    }

    is RouteDescriptor.Generator ->
        GeneratorRoute(
            GeneratorRoute.Args(
                context = GeneratorRoute.Args.Context(uris = uris.toTypedArray()),
                username = username,
                password = password,
                sshKey = sshKey,
                gpgKey = gpgKey,
                storageKey = storageKey,
            ),
        )

    is RouteDescriptor.Watchtower ->
        WatchtowerRoute(WatchtowerRoute.Args(filter = filter))

    RouteDescriptor.GpgTools -> GpgToolsRoute
    RouteDescriptor.Settings -> SettingsRoute
    RouteDescriptor.Downloads -> AttachmentsRoute()
    is RouteDescriptor.WatchtowerAlerts -> WatchtowerAlertsRoute(WatchtowerAlertsRoute.Args(filter = filter))
    RouteDescriptor.CipherFilters -> CipherFiltersRoute
    RouteDescriptor.GeneratorHistory -> GeneratorHistoryRoute
    RouteDescriptor.EmailRelayList -> EmailRelayListRoute
    RouteDescriptor.WordlistList -> WordlistListRoute
    RouteDescriptor.Feedback -> FeedbackRoute
    RouteDescriptor.Subscriptions -> SubscriptionsSettingsRoute
    RouteDescriptor.TwoFaServices -> TwoFaServicesRoute
    RouteDescriptor.PasskeysServices -> PasskeysServicesRoute
    RouteDescriptor.JustGetMyDataServices -> JustGetMyDataServicesRoute
    RouteDescriptor.JustDeleteMeServices -> JustDeleteMeServicesRoute

    is RouteDescriptor.CipherFilterView -> null

    // The descriptor intentionally carries no password, so the route can not
    // be reconstructed from it.
    RouteDescriptor.PasswordMemory -> null
    is RouteDescriptor.Unmapped -> null
}
