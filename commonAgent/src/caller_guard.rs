//! Per-request revalidation of a retained public-agent peer identity.
//!
//! An approval may only be reused while the accepted peer is still the
//! process that was verified at accept time. Helpers keep one guard per
//! connection and re-sample it immediately before each sensitive operation.

use thiserror::Error;

/// Retained platform identity of one accepted public-agent connection.
///
/// A guard without retained identity (connection scope, or a platform without
/// a retained collector) always revalidates successfully.
#[derive(Debug, Default)]
pub struct CallerGuard {
    #[cfg(target_os = "macos")]
    macos: Option<crate::macos::MacosPeerIdentity>,
    #[cfg(target_os = "linux")]
    linux: Option<crate::linux_identity::LinuxProcessIdentity>,
}

impl CallerGuard {
    /// Retains the audit-token identity collected for an accepted macOS peer.
    #[cfg(target_os = "macos")]
    #[must_use]
    pub fn from_macos(identity: Option<crate::macos::MacosPeerIdentity>) -> Self {
        Self { macos: identity }
    }

    /// Retains the pidfd identity collected for an accepted Linux peer.
    #[cfg(target_os = "linux")]
    #[must_use]
    pub fn from_linux(identity: Option<crate::linux_identity::LinuxProcessIdentity>) -> Self {
        Self { linux: identity }
    }

    /// The retained macOS peer identity, if any.
    #[cfg(target_os = "macos")]
    #[must_use]
    pub fn macos(&self) -> Option<&crate::macos::MacosPeerIdentity> {
        self.macos.as_ref()
    }

    /// The retained Linux peer identity, if any.
    #[cfg(target_os = "linux")]
    #[must_use]
    pub fn linux(&self) -> Option<&crate::linux_identity::LinuxProcessIdentity> {
        self.linux.as_ref()
    }

    /// Re-samples the retained identity.
    ///
    /// # Errors
    ///
    /// Returns [`CallerChanged`] when the retained peer exited, exec'd, or no
    /// longer matches its accepted identity. Callers must refuse the request.
    pub fn revalidate(&self) -> Result<(), CallerChanged> {
        #[cfg(target_os = "macos")]
        if let Some(identity) = &self.macos {
            identity.revalidate().map_err(CallerChanged::Macos)?;
        }
        #[cfg(target_os = "linux")]
        if let Some(identity) = &self.linux {
            identity.revalidate().map_err(CallerChanged::Linux)?;
        }
        Ok(())
    }
}

/// The retained caller identity no longer matches the accepted peer.
#[derive(Debug, Error)]
pub enum CallerChanged {
    /// The macOS audit-token or code-signing identity changed.
    #[cfg(target_os = "macos")]
    #[error("macOS caller identity changed: {0}")]
    Macos(crate::macos::MacosIdentityError),
    /// The Linux pidfd process identity changed.
    #[cfg(target_os = "linux")]
    #[error("Linux caller identity changed: {0}")]
    Linux(crate::linux_identity::LinuxIdentityError),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_without_retained_identity_revalidates() {
        assert!(CallerGuard::default().revalidate().is_ok());
    }
}
