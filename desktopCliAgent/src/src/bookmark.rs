//! Receiving an implicit security-scoped bookmark grants the broker access to a
//! directory selected in the parent app. Sandbox inheritance alone is insufficient.
use anyhow::{Context, Result, ensure};
use std::{
    ffi::{c_char, c_void},
    path::Path,
};

type Cf = *const c_void;
#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFDataCreate(allocator: Cf, bytes: *const u8, length: isize) -> Cf;
    fn CFURLCreateByResolvingBookmarkData(
        allocator: Cf,
        data: Cf,
        options: usize,
        relative: Cf,
        resource_properties: Cf,
        stale: *mut u8,
        error: *mut Cf,
    ) -> Cf;
    fn CFURLStopAccessingSecurityScopedResource(url: Cf);
    fn CFURLCopyFileSystemPath(url: Cf, style: isize) -> Cf;
    fn CFStringGetCString(string: Cf, buffer: *mut c_char, size: isize, encoding: u32) -> u8;
    fn CFRelease(value: Cf);
}

struct OwnedCf(Cf);
impl Drop for OwnedCf {
    fn drop(&mut self) {
        // SAFETY: each OwnedCf is created only from a non-null retained CF result.
        unsafe {
            CFRelease(self.0);
        }
    }
}

pub(crate) struct DirectoryGrant {
    url: OwnedCf,
}
// SAFETY: this immutable retained CFURL is only used for a balanced stop on drop;
// CoreFoundation reference counting and security-scoped URL access are thread-safe.
unsafe impl Send for DirectoryGrant {}
// SAFETY: shared access never mutates the CFURL or releases it before final drop.
unsafe impl Sync for DirectoryGrant {}

impl DirectoryGrant {
    pub fn open(bookmark: &[u8], expected_path: &Path) -> Result<Self> {
        ensure!(bookmark.len() <= 96 * 1024, "invalid_bookmark");
        // SAFETY: bookmark is valid for its length and CFDataCreate copies it.
        let data =
            unsafe { CFDataCreate(std::ptr::null(), bookmark.as_ptr(), bookmark.len() as isize) };
        ensure!(!data.is_null(), "invalid_bookmark");
        let data = OwnedCf(data);
        let mut stale = 0;
        // SAFETY: data is a valid retained CFData and all optional CF arguments are null.
        // Without UI/mounting: directory authorization must come from the parent picker.
        let url = unsafe {
            CFURLCreateByResolvingBookmarkData(
                std::ptr::null(),
                data.0,
                (1 << 8) | (1 << 9),
                std::ptr::null(),
                std::ptr::null(),
                &mut stale,
                std::ptr::null_mut(),
            )
        };
        ensure!(!url.is_null(), "bookmark_unavailable");
        let url = OwnedCf(url);
        // Implicit-scope bookmark resolution starts access. Balance that on drop,
        // without starting a second, separately counted resource access.
        let grant = Self { url };
        // SAFETY: CFURLCopyFileSystemPath returns a retained CFString for a live URL.
        let path = unsafe { CFURLCopyFileSystemPath(grant.url.0, 0) };
        ensure!(!path.is_null(), "bookmark_unavailable");
        let path = OwnedCf(path);
        let mut buffer = vec![0u8; 16384];
        ensure!(
            // SAFETY: buffer is writable and path is a valid CFString; 0x08000100 is UTF-8.
            unsafe {
                CFStringGetCString(
                    path.0,
                    buffer.as_mut_ptr().cast(),
                    buffer.len() as isize,
                    0x0800_0100,
                )
            } != 0,
            "bookmark_unavailable"
        );
        let length = buffer
            .iter()
            .position(|b| *b == 0)
            .context("bookmark_unavailable")?;
        let path = std::str::from_utf8(&buffer[..length]).context("bookmark_unavailable")?;
        ensure!(
            std::fs::canonicalize(path)? == std::fs::canonicalize(expected_path)?,
            "bookmark_path_mismatch"
        );
        Ok(grant)
    }
}

impl Drop for DirectoryGrant {
    fn drop(&mut self) {
        // SAFETY: implicit bookmark resolution granted access to this live URL;
        // stop releases the grant and OwnedCf releases the URL afterward.
        unsafe {
            CFURLStopAccessingSecurityScopedResource(self.url.0);
        }
    }
}
