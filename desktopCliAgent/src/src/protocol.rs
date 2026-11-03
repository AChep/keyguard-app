//! The private channel to the app: `proto/cli_agent.proto`. Its messages
//! can hold values and the launch token, so [`Private`] wipes them.
use zeroize::Zeroize;

#[cfg(test)]
mod fixtures;

// Not every generated helper is used by the agent.
#[allow(dead_code)]
mod generated {
    include!(concat!(env!("OUT_DIR"), "/keyguard.cliagent.rs"));
}

pub(crate) use generated::*;

/// A message that can hold values or the launch token, wiped when dropped.
pub(crate) struct Private<T: Wipe>(pub(crate) T);

impl<T: Wipe> Drop for Private<T> {
    fn drop(&mut self) {
        self.0.wipe();
    }
}

/// Zeroes the values and the token that a message can hold.
pub(crate) trait Wipe {
    fn wipe(&mut self);
}

// Every case is listed, so that a new one decides whether it holds values.
impl Wipe for Request {
    fn wipe(&mut self) {
        let Some(request) = &mut self.request else {
            return;
        };
        match request {
            request::Request::Authenticate(authenticate) => authenticate.token.zeroize(),
            request::Request::Manage(manage) => {
                for variable in &mut manage.variables {
                    variable.value.zeroize();
                }
            }
            request::Request::Cli(cli) => cli.request.zeroize(),
            request::Request::Mounts(_)
            | request::Request::WatchMounts(_)
            | request::Request::MountStatus(_)
            | request::Request::ResolveMount(_) => {}
        }
    }
}

impl Wipe for response::Response {
    fn wipe(&mut self) {
        match self {
            Self::Mounts(mounts) => {
                for mount in &mut mounts.mounts {
                    mount.bookmark.zeroize();
                }
            }
            Self::ResolveMount(resolved) => resolved.content.zeroize(),
            Self::Cli(cli) => cli.result.zeroize(),
            Self::Authenticate(_) | Self::Manage(_) | Self::MountStatus(_) | Self::Error(_) => {}
        }
    }
}
