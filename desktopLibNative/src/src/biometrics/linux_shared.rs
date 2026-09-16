//! Target-independent pieces of the Linux polkit backend. Kept separate from
//! `linux.rs` so the decision logic compiles and is unit tested on every host,
//! while the D-Bus calls themselves only build on Linux.

use super::ChallengeStatus;

/// polkit action that gates the unlock. Must match the `id` inside the
/// policy file.
pub(crate) const ACTION_ID: &str = "com.artemchep.keyguard.unlock";

/// `org.freedesktop.PolicyKit1.CheckAuthorizationFlags.AllowUserInteraction`.
pub(crate) const FLAG_ALLOW_USER_INTERACTION: u32 = 1;

/// Host location polkitd reads action descriptions from.
pub(crate) const POLICY_PATH: &str = "/usr/share/polkit-1/actions/com.artemchep.keyguard.policy";

/// Single source of truth for the policy; Gradle ships the same file inside
/// the Linux distribution.
pub(crate) const POLICY_XML: &str = include_str!("linux/com.artemchep.keyguard.policy");

/// Shell snippet run through `pkexec` to install [POLICY_XML], which is
/// supplied on stdin. The temporary file + rename keeps polkitd's inotify
/// watcher from ever parsing a half-written policy.
pub(crate) const INSTALL_POLICY_SCRIPT: &str = concat!(
    "set -e; ",
    "d=/usr/share/polkit-1/actions; ",
    "t=$(mktemp \"$d/.com.artemchep.keyguard.XXXXXX\"); ",
    "cat >\"$t\"; ",
    "chmod 0644 \"$t\"; ",
    "mv -f \"$t\" \"$d/com.artemchep.keyguard.policy\"",
);

/// Detail key polkit sets when the user dismissed the authentication dialog.
pub(crate) const DETAIL_DISMISSED: &str = "polkit.dismissed";

/// Mirrors the detection in the Kotlin `LePlatform.kt`, which checks the same
/// environment variable and file; keep both in sync.
pub(crate) fn is_flatpak() -> bool {
    std::env::var_os("container").is_some_and(|value| value == "flatpak")
        || std::path::Path::new("/.flatpak-info").exists()
}

/// Maps the `(bba{ss})` reply of `CheckAuthorization` to a status.
pub(crate) fn classify_authorization(
    is_authorized: bool,
    is_challenge: bool,
    dismissed: Option<&str>,
) -> (ChallengeStatus, Option<&'static str>) {
    if is_authorized {
        return (ChallengeStatus::Success, None);
    }
    if dismissed == Some("true") {
        return (ChallengeStatus::UserCanceled, None);
    }
    if is_challenge {
        // polkit could not prompt: no authentication agent is registered
        // for this session.
        return (
            ChallengeStatus::Unavailable,
            Some("No polkit authentication agent is running"),
        );
    }
    (ChallengeStatus::Unknown, Some("Authentication failed"))
}

/// Maps a D-Bus method error returned by `CheckAuthorization`.
pub(crate) fn classify_method_error(
    name: &str,
    message: Option<&str>,
) -> (ChallengeStatus, Option<String>) {
    if name.ends_with(".Error.Cancelled") {
        return (ChallengeStatus::UserCanceled, None);
    }
    if name == "org.freedesktop.DBus.Error.ServiceUnknown"
        || name == "org.freedesktop.DBus.Error.NameHasNoOwner"
    {
        return (
            ChallengeStatus::Unavailable,
            Some(format!("polkit is not available: {name}")),
        );
    }
    if message.is_some_and(|message| message.contains("is not registered")) {
        return (
            ChallengeStatus::PolicyNotInstalled,
            Some(format!("polkit action {ACTION_ID} is not registered")),
        );
    }
    let detail = message.unwrap_or("no details");
    (
        ChallengeStatus::Unknown,
        Some(format!("polkit call failed: {name}: {detail}")),
    )
}

/// `true` when polkit could not parse the subject we sent, which is how a
/// polkit older than 124 reacts to the `pidfd` key. Any other error is a
/// genuine failure that must not be retried with another subject.
pub(crate) fn is_subject_rejection(name: &str, message: Option<&str>) -> bool {
    name == "org.freedesktop.PolicyKit1.Error.Failed"
        && message.is_some_and(|message| message.to_ascii_lowercase().contains("subject"))
}

/// Turns the exit status of the `pkexec` helper into an error status and message, or
/// `None` when the policy was installed.
pub(crate) fn classify_pkexec_exit(
    code: Option<i32>,
    stderr: &str,
) -> Option<(ChallengeStatus, String)> {
    let message = match code {
        Some(0) => return None,
        // pkexec: the user dismissed the authentication dialog.
        Some(126) => {
            return Some((
                ChallengeStatus::UserCanceled,
                "Installing the polkit policy was cancelled".to_owned(),
            ))
        }
        // pkexec: the user is not authorized, or pkexec itself failed.
        Some(127) => "Not authorized to install the polkit policy".to_owned(),
        Some(code) => {
            let stderr = stderr.trim();
            if stderr.is_empty() {
                format!("Installing the polkit policy failed with code {code}")
            } else {
                format!("Installing the polkit policy failed with code {code}: {stderr}")
            }
        }
        None => "Installing the polkit policy was terminated by a signal".to_owned(),
    };
    Some((ChallengeStatus::PolicyNotInstalled, message))
}

#[cfg(test)]
mod tests {
    use super::{
        classify_authorization, classify_method_error, classify_pkexec_exit, is_subject_rejection,
        ACTION_ID, INSTALL_POLICY_SCRIPT, POLICY_PATH, POLICY_XML,
    };
    use crate::biometrics::ChallengeStatus;

    #[test]
    fn policy_declares_the_gating_action() {
        assert!(POLICY_XML.contains(&format!("<action id=\"{ACTION_ID}\">")));
        assert!(POLICY_XML.contains("<allow_active>auth_self</allow_active>"));
        assert!(POLICY_XML.contains("<allow_any>no</allow_any>"));
        assert!(POLICY_XML.contains("<allow_inactive>no</allow_inactive>"));
    }

    #[test]
    fn install_script_targets_the_policy_path() {
        let file_name = POLICY_PATH.rsplit('/').next().unwrap();
        assert!(INSTALL_POLICY_SCRIPT.contains(file_name));
        assert!(INSTALL_POLICY_SCRIPT.contains("chmod 0644"));
    }

    #[test]
    fn authorized_reply_is_success() {
        assert_eq!(
            classify_authorization(true, false, None),
            (ChallengeStatus::Success, None),
        );
    }

    #[test]
    fn dismissed_reply_is_user_canceled() {
        assert_eq!(
            classify_authorization(false, false, Some("true")),
            (ChallengeStatus::UserCanceled, None),
        );
    }

    #[test]
    fn challenge_without_agent_is_unavailable() {
        let (status, error) = classify_authorization(false, true, None);
        assert_eq!(status, ChallengeStatus::Unavailable);
        assert!(error.is_some());
    }

    #[test]
    fn denied_reply_is_unknown() {
        let (status, error) = classify_authorization(false, false, Some("false"));
        assert_eq!(status, ChallengeStatus::Unknown);
        assert_eq!(error, Some("Authentication failed"));
    }

    #[test]
    fn cancelled_error_is_user_canceled() {
        let (status, _) = classify_method_error("org.freedesktop.PolicyKit1.Error.Cancelled", None);
        assert_eq!(status, ChallengeStatus::UserCanceled);
    }

    #[test]
    fn unregistered_action_error_is_policy_not_installed() {
        let (status, _) = classify_method_error(
            "org.freedesktop.PolicyKit1.Error.Failed",
            Some("Action com.artemchep.keyguard.unlock is not registered"),
        );
        assert_eq!(status, ChallengeStatus::PolicyNotInstalled);
    }

    #[test]
    fn missing_service_is_unavailable() {
        let (status, _) = classify_method_error("org.freedesktop.DBus.Error.ServiceUnknown", None);
        assert_eq!(status, ChallengeStatus::Unavailable);
    }

    #[test]
    fn other_errors_are_unknown_with_details() {
        let (status, error) =
            classify_method_error("org.freedesktop.PolicyKit1.Error.Failed", Some("boom"));
        assert_eq!(status, ChallengeStatus::Unknown);
        assert!(error.unwrap().contains("boom"));
    }

    #[test]
    fn only_subject_parse_errors_fall_back_to_the_bus_name() {
        assert!(is_subject_rejection(
            "org.freedesktop.PolicyKit1.Error.Failed",
            Some("Error parsing unix-process subject: Key pid is not present in dictionary"),
        ));
        assert!(is_subject_rejection(
            "org.freedesktop.PolicyKit1.Error.Failed",
            Some("Error getting subject: no such process"),
        ));
        for (name, message) in [
            (
                "org.freedesktop.PolicyKit1.Error.Failed",
                Some("Action com.artemchep.keyguard.unlock is not registered"),
            ),
            ("org.freedesktop.PolicyKit1.Error.Failed", None),
            (
                "org.freedesktop.PolicyKit1.Error.Cancelled",
                Some("subject"),
            ),
            ("org.freedesktop.DBus.Error.ServiceUnknown", Some("subject")),
            ("org.freedesktop.DBus.Error.NoReply", Some("subject")),
        ] {
            assert!(!is_subject_rejection(name, message), "{name}: {message:?}");
        }
    }

    #[test]
    fn pkexec_exit_codes_are_described() {
        assert_eq!(classify_pkexec_exit(Some(0), ""), None);
        let (status, message) = classify_pkexec_exit(Some(126), "").unwrap();
        assert_eq!(status, ChallengeStatus::UserCanceled);
        assert!(message.contains("cancelled"));
        for code in [Some(127), Some(1), None] {
            let (status, message) = classify_pkexec_exit(code, " oops \n").unwrap();
            assert_eq!(status, ChallengeStatus::PolicyNotInstalled);
            assert!(!message.is_empty());
        }
        assert!(classify_pkexec_exit(Some(127), "")
            .unwrap()
            .1
            .contains("Not authorized"));
        assert!(classify_pkexec_exit(Some(1), " oops \n")
            .unwrap()
            .1
            .ends_with("oops"));
    }
}
