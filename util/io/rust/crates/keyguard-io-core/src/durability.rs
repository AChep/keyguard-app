//! Synchronization levels requested by callers and reported after commit.

/// A completed operating-system synchronization protocol.
///
/// These levels describe operations performed by the platform. They do not
/// promise that a filesystem, device, controller, or virtualized storage stack
/// survives power loss contrary to its documented behavior.
#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum SyncLevel {
    /// Publication is atomic with respect to process failure; no persistence
    /// barrier is requested.
    ProcessAtomic = 0,
    /// Finalized staged bytes and file metadata are synchronized before
    /// publication.
    FileSynchronized = 1,
    /// File synchronization is followed by every applicable namespace
    /// synchronization operation owned by the transaction.
    FileAndNamespaceSynchronized = 2,
}

impl TryFrom<i32> for SyncLevel {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::ProcessAtomic),
            1 => Ok(Self::FileSynchronized),
            2 => Ok(Self::FileAndNamespaceSynchronized),
            _ => Err(()),
        }
    }
}

/// Synchronization enforcement selected by the caller.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SyncPolicy {
    /// Fail unless the exact requested level can be attempted.
    Required(SyncLevel),
    /// Prefer `preferred`, while permitting a known platform-capability
    /// downgrade no lower than `minimum`.
    Prefer {
        /// Strongest level the caller would like to establish.
        preferred: SyncLevel,
        /// Weakest level at which the caller may accept success.
        minimum: SyncLevel,
    },
}

/// Failure to validate or negotiate a synchronization policy.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SyncPolicyError {
    /// `minimum` exceeded `preferred`.
    InvalidOrder,
    /// The platform maximum is below the required or minimum level.
    Unavailable,
}

impl SyncPolicy {
    /// Validates the policy and selects a level no stronger than
    /// `platform_maximum`.
    ///
    /// A lower selection is permitted only by [`SyncPolicy::Prefer`]. Runtime
    /// I/O failures are deliberately outside negotiation.
    ///
    /// # Errors
    ///
    /// Returns [`SyncPolicyError::InvalidOrder`] for an inverted preferred and
    /// minimum range. Returns [`SyncPolicyError::Unavailable`] when the
    /// platform maximum is below the required level or preferred policy's
    /// minimum.
    pub const fn negotiate(
        self,
        platform_maximum: SyncLevel,
    ) -> Result<SyncLevel, SyncPolicyError> {
        match self {
            Self::Required(level) => {
                if level as i32 <= platform_maximum as i32 {
                    Ok(level)
                } else {
                    Err(SyncPolicyError::Unavailable)
                }
            }
            Self::Prefer { preferred, minimum } => {
                if minimum as i32 > preferred as i32 {
                    return Err(SyncPolicyError::InvalidOrder);
                }
                let selected = if preferred as i32 <= platform_maximum as i32 {
                    preferred
                } else {
                    platform_maximum
                };
                if selected as i32 >= minimum as i32 {
                    Ok(selected)
                } else {
                    Err(SyncPolicyError::Unavailable)
                }
            }
        }
    }

    /// Applies a capability ceiling learned from the current filesystem before
    /// publication.
    ///
    /// Only [`SyncPolicy::Prefer`] may select a lower level. Required policies
    /// fail when the learned ceiling is below their exact level.
    ///
    /// # Errors
    ///
    /// Returns [`SyncPolicyError::Unavailable`] when the learned ceiling is
    /// below a required level or below a preferred policy's minimum.
    pub const fn negotiate_capability(
        self,
        current: SyncLevel,
        capability_maximum: SyncLevel,
    ) -> Result<SyncLevel, SyncPolicyError> {
        let ceiling = if current as i32 <= capability_maximum as i32 {
            current
        } else {
            capability_maximum
        };
        match self {
            Self::Required(level) => {
                if ceiling as i32 >= level as i32 {
                    Ok(level)
                } else {
                    Err(SyncPolicyError::Unavailable)
                }
            }
            Self::Prefer { minimum, .. } => {
                if ceiling as i32 >= minimum as i32 {
                    Ok(ceiling)
                } else {
                    Err(SyncPolicyError::Unavailable)
                }
            }
        }
    }

    /// Preferred synchronization level represented by this policy.
    #[must_use]
    pub const fn preferred(self) -> SyncLevel {
        match self {
            Self::Required(level) => level,
            Self::Prefer { preferred, .. } => preferred,
        }
    }

    /// Minimum synchronization level represented by this policy.
    #[must_use]
    pub const fn minimum(self) -> SyncLevel {
        match self {
            Self::Required(level) => level,
            Self::Prefer { minimum, .. } => minimum,
        }
    }
}

/// Synchronization level actually achieved by a commit.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum AchievedSyncLevel {
    /// Atomic process-visible publication without a persistence barrier.
    ProcessAtomic = 0,
    /// Staged file bytes and metadata were synchronized before publication.
    FileSynchronized = 1,
    /// File and applicable namespace synchronization operations completed.
    FileAndNamespaceSynchronized = 2,
}

impl AchievedSyncLevel {
    /// Returns the achieved level equivalent to a selected level.
    #[must_use]
    pub const fn from_selected(level: SyncLevel) -> Self {
        match level {
            SyncLevel::ProcessAtomic => Self::ProcessAtomic,
            SyncLevel::FileSynchronized => Self::FileSynchronized,
            SyncLevel::FileAndNamespaceSynchronized => Self::FileAndNamespaceSynchronized,
        }
    }

    /// Returns the weaker of two levels.
    #[must_use]
    pub fn min(self, other: Self) -> Self {
        if (self as u8) <= (other as u8) {
            self
        } else {
            other
        }
    }
}

/// Strongest synchronization level the current platform contract advertises.
///
/// Runtime filesystem capability outcomes may establish a lower ceiling
/// before publication. Only a caller-selected [`SyncPolicy::Prefer`] range may
/// accept that downgrade.
#[must_use]
pub const fn platform_max_sync_level() -> SyncLevel {
    if cfg!(windows) {
        SyncLevel::FileSynchronized
    } else {
        SyncLevel::FileAndNamespaceSynchronized
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_maximum_matches_the_target_contract() {
        let expected = if cfg!(windows) {
            SyncLevel::FileSynchronized
        } else {
            SyncLevel::FileAndNamespaceSynchronized
        };
        assert_eq!(platform_max_sync_level(), expected);
    }

    #[test]
    fn achieved_level_degrades_towards_the_weaker_guarantee() {
        assert_eq!(
            AchievedSyncLevel::FileAndNamespaceSynchronized
                .min(AchievedSyncLevel::FileSynchronized),
            AchievedSyncLevel::FileSynchronized
        );
        assert_eq!(
            AchievedSyncLevel::ProcessAtomic.min(AchievedSyncLevel::FileAndNamespaceSynchronized),
            AchievedSyncLevel::ProcessAtomic
        );
    }

    #[test]
    fn wire_values_round_trip() {
        for value in 0..=2 {
            let level = SyncLevel::try_from(value).expect("level must parse");
            assert_eq!(level as i32, value);
        }
        assert!(SyncLevel::try_from(3).is_err());
        assert!(SyncLevel::try_from(-1).is_err());
    }

    #[test]
    fn required_never_downgrades() {
        assert_eq!(
            SyncPolicy::Required(SyncLevel::FileAndNamespaceSynchronized)
                .negotiate(SyncLevel::FileSynchronized),
            Err(SyncPolicyError::Unavailable)
        );
    }

    #[test]
    fn prefer_negotiates_only_inside_the_validated_range() {
        assert_eq!(
            SyncPolicy::Prefer {
                preferred: SyncLevel::FileAndNamespaceSynchronized,
                minimum: SyncLevel::FileSynchronized,
            }
            .negotiate(SyncLevel::FileSynchronized),
            Ok(SyncLevel::FileSynchronized)
        );
        assert_eq!(
            SyncPolicy::Prefer {
                preferred: SyncLevel::FileAndNamespaceSynchronized,
                minimum: SyncLevel::FileSynchronized,
            }
            .negotiate(SyncLevel::ProcessAtomic),
            Err(SyncPolicyError::Unavailable)
        );
        assert_eq!(
            SyncPolicy::Prefer {
                preferred: SyncLevel::ProcessAtomic,
                minimum: SyncLevel::FileSynchronized,
            }
            .negotiate(SyncLevel::FileAndNamespaceSynchronized),
            Err(SyncPolicyError::InvalidOrder)
        );
    }
}
