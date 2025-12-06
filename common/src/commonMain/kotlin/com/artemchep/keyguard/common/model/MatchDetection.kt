package com.artemchep.keyguard.common.model;

import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.uri_match_detection_default_note
import com.artemchep.keyguard.res.uri_match_detection_domain_note
import com.artemchep.keyguard.res.uri_match_detection_exact_note
import com.artemchep.keyguard.res.uri_match_detection_host_note
import com.artemchep.keyguard.res.uri_match_detection_never_note
import com.artemchep.keyguard.res.uri_match_detection_regex_note
import com.artemchep.keyguard.res.uri_match_detection_startswith_note

enum class MatchDetection(
    val key: String,
    val matchType: DSecret.Uri.MatchType?,
) {
    Default(
        key = "default",
        matchType = null,
    ),
    Domain(
        key = "domain",
        matchType = DSecret.Uri.MatchType.Domain,
    ),
    Host(
        key = "host",
        matchType = DSecret.Uri.MatchType.Host,
    ),
    StartsWith(
        key = "starts_with",
        matchType = DSecret.Uri.MatchType.StartsWith,
    ),
    Exact(
        key = "exact",
        matchType = DSecret.Uri.MatchType.Exact,
    ),
    RegularExpression(
        key = "regex",
        matchType = DSecret.Uri.MatchType.RegularExpression,
    ),
    Never(
        key = "never",
        matchType = DSecret.Uri.MatchType.Never,
    );

    companion object {
        fun valueOf(
            matchType: DSecret.Uri.MatchType?,
        ): MatchDetection = when (matchType) {
            null -> Default
            DSecret.Uri.MatchType.Domain -> Domain
            DSecret.Uri.MatchType.Host -> Host
            DSecret.Uri.MatchType.StartsWith -> StartsWith
            DSecret.Uri.MatchType.Exact -> Exact
            DSecret.Uri.MatchType.RegularExpression -> RegularExpression
            DSecret.Uri.MatchType.Never -> Never
        }
    }
}

/**
 * Builds a mapping of docs specifically for the different
 * [MatchDetection] variants.
 */
suspend fun MatchDetection.Companion.buildDocs(
    translatorScope: TranslatorScope,
) = mapOf(
    MatchDetection.Default.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_default_note),
        url = "https://bitwarden.com/help/uri-match-detection/#default-match-detection",
    ),
    MatchDetection.Domain.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_domain_note),
        url = "https://bitwarden.com/help/uri-match-detection/#base-domain",
    ),
    MatchDetection.Host.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_host_note),
        url = "https://bitwarden.com/help/uri-match-detection/#host",
    ),
    MatchDetection.StartsWith.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_startswith_note),
        url = "https://bitwarden.com/help/uri-match-detection/#starts-with",
    ),
    MatchDetection.Exact.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_exact_note),
        url = "https://bitwarden.com/help/uri-match-detection/#regular-expression",
    ),
    MatchDetection.RegularExpression.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_regex_note),
        url = "https://bitwarden.com/help/uri-match-detection/#regular-expression",
    ),
    MatchDetection.Never.name to ConfirmationRoute.Args.Item.EnumItem.Doc(
        text = translatorScope.translate(Res.string.uri_match_detection_never_note),
        url = "https://bitwarden.com/help/uri-match-detection/#exact",
    ),
)
