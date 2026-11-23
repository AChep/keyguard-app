use crate::{Failure, Operation, Status};

pub(crate) trait StartupTask {
    fn status(&self) -> Result<Status, Failure>;
    fn enable(&self) -> Result<Status, Failure>;
    fn disable(&self) -> Result<(), Failure>;
}

/// Runs for a process with package identity.
pub(crate) fn execute_package<T: StartupTask>(
    operation: Operation,
    version: impl FnOnce() -> Result<String, Failure>,
    task: impl FnOnce() -> Result<T, Failure>,
) -> Result<Status, Failure> {
    if operation != Operation::Disable && !supports_startup_parameters(&version()?) {
        return Ok(Status::Unavailable);
    }
    let task = task()?;
    let current = task.status()?;
    if !can_mutate(operation, current) {
        return Ok(current);
    }
    if operation == Operation::Enable {
        return task.enable();
    }
    task.disable()?;
    task.status()
}

/// AnalyticsInfo encodes major, minor, build, and revision as four 16-bit fields.
pub(crate) fn supports_startup_parameters(device_family_version: &str) -> bool {
    if !device_family_version
        .bytes()
        .all(|value| value.is_ascii_digit())
    {
        return false;
    }
    let Ok(version) = device_family_version.parse::<u64>() else {
        return false;
    };
    // uap10:Parameters was introduced in Windows 10, version 2004 (19041).
    let major = (version >> 48) as u16;
    let minor = (version >> 32) as u16;
    let build = (version >> 16) as u16;
    (major, minor, build) >= (10, 0, 19_041)
}

/// Explorer's `StartupApproved\StartupFolder` value for a Startup shortcut. Task Manager
/// and Settings set bit 0 of the first byte to disable it; a missing or empty value is approved.
pub(crate) fn shortcut_approval(value: Option<&[u8]>) -> Status {
    match value.and_then(<[u8]>::first) {
        Some(flags) if flags & 1 == 1 => Status::DisabledByUser,
        _ => Status::Enabled,
    }
}

/// Values from the WinRT Windows.ApplicationModel.StartupTaskState contract.
pub(crate) fn state(value: i32) -> Status {
    match value {
        0 => Status::Disabled,
        1 => Status::DisabledByUser,
        2 => Status::Enabled,
        3 => Status::DisabledByPolicy,
        4 => Status::EnabledByPolicy,
        _ => Status::Unknown,
    }
}

pub(crate) fn can_mutate(operation: Operation, current: Status) -> bool {
    matches!(
        (operation, current),
        (Operation::Enable, Status::Disabled) | (Operation::Disable, Status::Enabled)
    )
}

#[cfg(test)]
#[path = "windows_startup_tests.rs"]
mod adapter_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use Operation::{Disable, Enable, Status as Query};

    #[test]
    fn gates_startup_parameters_on_the_real_windows_build() {
        let version = |major: u16, minor: u16, build: u16, revision: u16| {
            ((u64::from(major) << 48)
                | (u64::from(minor) << 32)
                | (u64::from(build) << 16)
                | u64::from(revision))
            .to_string()
        };
        for build in [18_362, 18_363, 19_040] {
            assert!(!supports_startup_parameters(&version(
                10,
                0,
                build,
                u16::MAX
            )));
        }
        for build in [19_041, 19_045, 22_000, 26_100] {
            assert!(supports_startup_parameters(&version(10, 0, build, 0)));
        }
        assert!(!supports_startup_parameters(&version(
            6,
            3,
            u16::MAX,
            u16::MAX
        )));
        assert!(supports_startup_parameters(&version(11, 0, 0, 0)));
    }

    #[test]
    fn malformed_windows_versions_never_enable_startup_parameters() {
        for version in [
            "",
            "0",
            "10.0.19041.0",
            "-1",
            "+2814751014977536",
            " 2814751014977536",
            "18446744073709551616",
        ] {
            assert!(
                !supports_startup_parameters(version),
                "accepted {version:?}"
            );
        }
    }

    #[test]
    fn preserves_policy_states_and_rejects_mutations() {
        for (value, expected) in [(3, Status::DisabledByPolicy), (4, Status::EnabledByPolicy)] {
            let current = state(value);
            assert_eq!(current, expected);
            for operation in [Query, Enable, Disable] {
                assert!(!can_mutate(operation, current));
            }
        }
    }

    #[test]
    fn changes_only_user_controllable_states() {
        assert_eq!(state(0), Status::Disabled);
        assert!(can_mutate(Enable, state(0)));
        assert!(!can_mutate(Disable, state(0)));
        assert_eq!(state(2), Status::Enabled);
        assert!(can_mutate(Disable, state(2)));
        assert!(!can_mutate(Enable, state(2)));
        for value in [0, 1, 2, 3, 4, 5, -1] {
            assert!(!can_mutate(Query, state(value)));
        }
    }

    #[test]
    fn shortcut_approval_follows_the_disabled_bit() {
        let timestamp = [0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0x01];
        let disabled = [&[0x03, 0, 0, 0][..], &timestamp].concat();
        assert_eq!(shortcut_approval(Some(&disabled)), Status::DisabledByUser);
        assert_eq!(shortcut_approval(Some(&[0x07])), Status::DisabledByUser);
        for value in [&[0x02, 0, 0, 0][..], &[0x06], &[0x00], &[]] {
            assert_eq!(shortcut_approval(Some(value)), Status::Enabled, "{value:?}");
        }
        assert_eq!(shortcut_approval(None), Status::Enabled);
    }

    #[test]
    fn task_manager_disable_outlives_app_disable_and_enable() {
        // The app never writes StartupApproved, so a value Task Manager wrote still
        // applies to the shortcut that a later enable recreates.
        let task_manager = [&[0x03, 0, 0, 0][..], &[0xff; 8]].concat();
        assert_eq!(
            shortcut_approval(Some(&task_manager)),
            Status::DisabledByUser
        );
    }

    #[test]
    fn user_revocation_and_future_states_are_read_only() {
        assert_eq!(state(1), Status::DisabledByUser);
        for value in [1, 5, -1] {
            if value != 1 {
                assert_eq!(state(value), Status::Unknown);
            }
            assert!(!can_mutate(Enable, state(value)));
            assert!(!can_mutate(Disable, state(value)));
        }
    }
}
