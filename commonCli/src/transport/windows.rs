use anyhow::{Result, ensure};
use keyguard_agent_identity::windows::open_current_user_process;
use std::{
    os::windows::io::{AsRawHandle, OwnedHandle},
    path::Path,
};
use windows_sys::Win32::{
    Foundation::WAIT_TIMEOUT,
    System::Threading::{
        PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE, QueryFullProcessImageNameW,
        WaitForSingleObject,
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
        let handle = open_current_user_process(
            pid,
            PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
        )?;
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
