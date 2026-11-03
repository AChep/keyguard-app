//! Best-effort Windows SSH approval grouping, not authenticated process origin.
//!
//! Retained handles and creation times prevent reuse after an observed process
//! exits. They cannot authenticate the writer of a transferred pipe handle or
//! prevent a client from choosing a different parent at process creation.

use crate::ipc::messages::{
    CallerAuthorizationEvidenceSource as Evidence, CallerAuthorizationSubject,
    CallerAuthorizationSubjectKind as Kind, CallerIdentity,
};
use keyguard_agent_identity::windows::process::{
    find_application, visible_window_processes, ProcessIdentity,
};
use keyguard_agent_identity::windows::{invalid_data, last_error};
use std::io;
use std::os::windows::io::AsRawHandle;
use windows_sys::Win32::System::Pipes::GetNamedPipeClientProcessId;

pub(crate) struct WindowsCallerIdentity {
    process: ProcessIdentity,
    application: Option<ProcessIdentity>,
}

impl WindowsCallerIdentity {
    pub(crate) fn from_pipe(
        pipe: &tokio::net::windows::named_pipe::NamedPipeServer,
    ) -> io::Result<Self> {
        let mut pid = 0;
        // SAFETY: the connected pipe stays alive throughout the call and pid
        // is writable storage. This API only attributes the original opener.
        if unsafe { GetNamedPipeClientProcessId(pipe.as_raw_handle().cast(), &mut pid) } == 0 {
            return Err(last_error("GetNamedPipeClientProcessId"));
        }
        let process = ProcessIdentity::open(pid)?;
        if !process.is_current_user()? {
            return Err(invalid_data("pipe opener belongs to a different user"));
        }
        let application = find_application(&process, &visible_window_processes());
        process.revalidate()?;
        Ok(Self {
            process,
            application,
        })
    }

    /// Return fresh evidence each time, omitting an application that has exited
    /// or changed security context. An expired direct peer rejects the request.
    pub(crate) fn update_caller(&self, caller: &mut CallerIdentity) -> io::Result<()> {
        self.process.revalidate()?;
        let application = self
            .application
            .as_ref()
            .filter(|app| app.revalidate().is_ok());
        let mut subjects = vec![subject(&self.process, Kind::Process)?];
        if let Some(app) = application {
            subjects.push(subject(app, Kind::ApplicationInstance)?);
        }
        caller.pid = self.process.pid();
        caller.process_name = self.process.name();
        caller.executable_path = self.process.path().to_owned();
        let app = application.unwrap_or(&self.process);
        caller.app_pid = app.pid();
        caller.app_name = app.name();
        caller.app_bundle_path = app.path().to_owned();
        if let Some(authorization) = caller.authorization.as_mut() {
            authorization.subjects = subjects;
        }
        Ok(())
    }
}

fn subject(process: &ProcessIdentity, kind: Kind) -> io::Result<CallerAuthorizationSubject> {
    let (domain, evidence): (&[u8], Evidence) = match kind {
        Kind::Process => (
            b"windows-pipe-opener-process-v1",
            Evidence::WindowsProcessSnapshot,
        ),
        Kind::ApplicationInstance => (
            b"windows-pipe-opener-application-v1",
            Evidence::WindowsApplicationSnapshot,
        ),
        _ => return Err(invalid_data("unsupported Windows subject kind")),
    };
    let fingerprint = process
        .subject_fingerprint(domain)
        .map_err(|error| invalid_data(error.to_string()))?;
    Ok(CallerAuthorizationSubject {
        kind: kind as i32,
        evidence_source: evidence as i32,
        fingerprint: fingerprint.into_bytes().to_vec(),
    })
}

#[cfg(test)]
mod tests;
