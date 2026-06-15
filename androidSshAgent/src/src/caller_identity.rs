use common_ssh_agent_rust::messages::CallerIdentity as ProtoCallerIdentity;
use common_ssh_agent_rust::unix_caller_identity::{
    caller_from_unix_stream as shared_caller_from_unix_stream, UnixCallerIdentity,
};
use tokio::net::UnixStream;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CallerIdentity {
    pub(crate) pid: Option<u32>,
    pub(crate) uid: u32,
    pub(crate) gid: u32,
    pub(crate) process_name: Option<String>,
    pub(crate) executable_path: Option<String>,
}

impl From<UnixCallerIdentity> for CallerIdentity {
    fn from(value: UnixCallerIdentity) -> Self {
        Self {
            pid: value.pid,
            uid: value.uid,
            gid: value.gid,
            process_name: value.process_name,
            executable_path: value.executable_path,
        }
    }
}

impl From<&CallerIdentity> for ProtoCallerIdentity {
    fn from(value: &CallerIdentity) -> Self {
        Self {
            pid: value.pid.unwrap_or(0),
            uid: value.uid,
            gid: value.gid,
            process_name: value.process_name.clone().unwrap_or_default(),
            executable_path: value.executable_path.clone().unwrap_or_default(),
            app_pid: 0,
            app_name: String::new(),
            app_bundle_path: String::new(),
        }
    }
}

pub(crate) fn caller_from_unix_stream(stream: &UnixStream) -> Option<CallerIdentity> {
    shared_caller_from_unix_stream(stream).map(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use common_ssh_agent_rust::unix_caller_identity::UnixCallerIdentity;

    #[test]
    fn caller_identity_maps_shared_identity() {
        let caller = CallerIdentity::from(UnixCallerIdentity {
            pid: Some(1234),
            uid: 42,
            gid: 77,
            process_name: Some("ssh".to_string()),
            executable_path: Some("/usr/bin/ssh".to_string()),
        });

        assert_eq!(caller.pid, Some(1234));
        assert_eq!(caller.uid, 42);
        assert_eq!(caller.gid, 77);
        assert_eq!(caller.process_name.as_deref(), Some("ssh"));
        assert_eq!(caller.executable_path.as_deref(), Some("/usr/bin/ssh"));
    }

    #[test]
    fn proto_caller_identity_preserves_process_metadata() {
        let caller = CallerIdentity {
            pid: Some(123),
            uid: 1000,
            gid: 1001,
            process_name: Some("ssh".to_string()),
            executable_path: Some("/usr/bin/ssh".to_string()),
        };

        let proto: ProtoCallerIdentity = (&caller).into();

        assert_eq!(proto.pid, 123);
        assert_eq!(proto.uid, 1000);
        assert_eq!(proto.gid, 1001);
        assert_eq!(proto.process_name, "ssh");
        assert_eq!(proto.executable_path, "/usr/bin/ssh");
        assert_eq!(proto.app_name, "");
        assert_eq!(proto.app_bundle_path, "");
    }
}
