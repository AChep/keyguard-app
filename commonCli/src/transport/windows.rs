use anyhow::{Result, ensure};
use keyguard_agent_identity::windows::{current_user_sid_string, token_user_sid_string};
use std::{
    os::windows::io::{AsHandle, AsRawHandle, FromRawHandle, OwnedHandle},
    path::Path,
};
use windows_sys::Win32::{
    Foundation::WAIT_TIMEOUT,
    Security::TOKEN_QUERY,
    System::Threading::{
        OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
        QueryFullProcessImageNameW, WaitForSingleObject,
    },
};

/// A process of this user, held open so that its ID can not be reused.
pub struct Process {
    handle: OwnedHandle,
    image: std::path::PathBuf,
}

impl Process {
    pub fn open(pid: u32) -> Result<Self> {
        use std::os::windows::ffi::OsStringExt;
        // SAFETY: this read-only access mask requests a process handle by PID.
        let process = unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                0,
                pid,
            )
        };
        ensure!(!process.is_null(), "peer_unavailable");
        // SAFETY: OpenProcess transferred this non-null owned handle.
        let handle = unsafe { OwnedHandle::from_raw_handle(process.cast()) };
        let mut token = std::ptr::null_mut();
        // SAFETY: handle is live and token is writable for the returned handle.
        let opened =
            unsafe { OpenProcessToken(handle.as_raw_handle().cast(), TOKEN_QUERY, &mut token) };
        ensure!(opened != 0, "peer_unavailable");
        // SAFETY: OpenProcessToken transferred this non-null owned handle.
        let token = unsafe { OwnedHandle::from_raw_handle(token.cast()) };
        ensure!(
            token_user_sid_string(token.as_handle())? == current_user_sid_string()?,
            "peer_owner_mismatch"
        );
        let mut buffer = vec![0u16; 32768];
        let mut size = buffer.len() as u32;
        // SAFETY: handle remains live and buffer has size writable UTF-16 units.
        let found = unsafe {
            QueryFullProcessImageNameW(
                handle.as_raw_handle().cast(),
                0,
                buffer.as_mut_ptr(),
                &mut size,
            )
        };
        ensure!(found != 0, "peer_unavailable");
        Ok(Self {
            handle,
            image: std::ffi::OsString::from_wide(&buffer[..size as usize]).into(),
        })
    }

    pub fn image(&self) -> &Path {
        &self.image
    }

    /// Opens the image file, so that it can not be changed or renamed
    /// while open.
    pub fn open_image(&self) -> std::io::Result<std::fs::File> {
        use std::os::windows::fs::OpenOptionsExt;
        const FILE_SHARE_READ: u32 = 1;
        std::fs::OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .open(&self.image)
    }

    pub fn is_running(&self) -> bool {
        // SAFETY: handle is a live process handle with synchronize access.
        unsafe { WaitForSingleObject(self.handle.as_raw_handle().cast(), 0) == WAIT_TIMEOUT }
    }
}
