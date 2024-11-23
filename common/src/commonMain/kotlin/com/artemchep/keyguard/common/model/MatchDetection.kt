package com.artemchep.keyguard.common.model;

enum class MatchDetection(
    val matchType: DSecret.Uri.MatchType?,
) {
    Default(null),
    Domain(DSecret.Uri.MatchType.Domain),
    Host(DSecret.Uri.MatchType.Host),
    StartsWith(DSecret.Uri.MatchType.StartsWith),
    Exact(DSecret.Uri.MatchType.Exact),
    RegularExpression(DSecret.Uri.MatchType.RegularExpression),
    Never(DSecret.Uri.MatchType.Never);

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
