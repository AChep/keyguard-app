package com.artemchep.keyguard.common

/**
 * Stable, app-owned directories that can directly contain native atomic-write
 * artifacts. User-selected directories are intentionally excluded.
 */
internal class PlatformTemporaryArtifactRootRegistry(val values: List<TemporaryArtifactRoot>)
