//! Windows ownership and bounded, cancellable local named-pipe transport.

use std::{
    ffi::OsStr,
    fs::File,
    io::{self, Read, Write},
    mem::{offset_of, size_of, zeroed},
    os::windows::{
        ffi::OsStrExt,
        io::{AsRawHandle, FromRawHandle, OwnedHandle},
    },
    path::{Component, Path, PathBuf, Prefix},
    ptr,
    sync::Arc,
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use keyguard_io_core::windows_file::{self, OpenOptions as FileOpenOptions};

use windows_sys::Win32::{
    Foundation::{
        ERROR_BROKEN_PIPE, ERROR_IO_PENDING, ERROR_LOCK_VIOLATION, ERROR_NO_DATA,
        ERROR_PIPE_CONNECTED, ERROR_PIPE_NOT_CONNECTED, GENERIC_READ, GENERIC_WRITE, HANDLE,
        INVALID_HANDLE_VALUE, LocalFree, WAIT_OBJECT_0, WAIT_TIMEOUT,
    },
    Security::{
        ACCESS_ALLOWED_ACE, ACE_HEADER, ACL,
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            GetSecurityInfo, SE_FILE_OBJECT,
        },
        DACL_SECURITY_INFORMATION, EqualSid, GetAce, GetSecurityDescriptorControl,
        GetSecurityDescriptorDacl, GetSecurityDescriptorOwner, GetSidSubAuthorityCount,
        GetTokenInformation, IsValidAcl, IsValidSecurityDescriptor, IsValidSid,
        OWNER_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, SE_DACL_PROTECTED, SECURITY_ATTRIBUTES,
        TOKEN_QUERY, TOKEN_USER, TokenUser,
    },
    Storage::FileSystem::{
        BY_HANDLE_FILE_INFORMATION, CREATE_NEW, CreateFileW, DELETE, FILE_ATTRIBUTE_DIRECTORY,
        FILE_ATTRIBUTE_REPARSE_POINT, FILE_FLAG_FIRST_PIPE_INSTANCE, FILE_FLAG_OVERLAPPED,
        FILE_SHARE_DELETE, FILE_SHARE_READ, FILE_SHARE_WRITE, FILE_TRAVERSE, GetDriveTypeW,
        GetFileInformationByHandle, GetVolumePathNameW, LOCKFILE_EXCLUSIVE_LOCK,
        LOCKFILE_FAIL_IMMEDIATELY, LockFileEx, OPEN_ALWAYS, OPEN_EXISTING, PIPE_ACCESS_DUPLEX,
        READ_CONTROL, ReadFile, WriteFile,
    },
    System::{
        IO::{CancelIoEx, GetOverlappedResult, OVERLAPPED},
        Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE,
            PIPE_REJECT_REMOTE_CLIENTS, PIPE_TYPE_BYTE, PIPE_WAIT,
        },
        Threading::{
            CreateEventW, GetCurrentProcess, INFINITE, OpenProcessToken, ResetEvent, SetEvent,
            WaitForMultipleObjects, WaitForSingleObject,
        },
    },
};

use crate::{
    Error, Events, Failure, Result,
    protocol::{self, ACK, ACK_LEN, REQUEST_LEN, TOKEN_LEN},
};

const CLIENT_LIMIT: usize = 4;
const CLIENT_TIMEOUT: Duration = Duration::from_secs(1);
const PIPE_PREFIX: &str = r"\\.\pipe\keyguard-instance-";

fn wide(value: &OsStr) -> Result<Vec<u16>> {
    let mut result: Vec<_> = value.encode_wide().collect();
    if result.contains(&0) {
        return Err(Error::InvalidArgument.into());
    }
    result.push(0);
    Ok(result)
}

fn last_error() -> Failure {
    io::Error::last_os_error().into()
}

fn owned(handle: HANDLE) -> Result<OwnedHandle> {
    if handle.is_null() || handle == INVALID_HANDLE_VALUE {
        return Err(last_error());
    }
    // SAFETY: Callers transfer unique ownership of a successful, closable Win32 handle.
    Ok(unsafe { OwnedHandle::from_raw_handle(handle) })
}

struct LocalAllocation(PSECURITY_DESCRIPTOR);

impl Drop for LocalAllocation {
    fn drop(&mut self) {
        // SAFETY: This allocation comes from a LocalAlloc-family security API.
        unsafe { LocalFree(self.0) };
    }
}

struct Security(LocalAllocation);

impl Security {
    fn current_user_sid() -> Result<String> {
        let mut token = ptr::null_mut();
        // SAFETY: GetCurrentProcess is a pseudo-handle; token is writable.
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return Err(last_error());
        }
        let token = owned(token)?;
        let mut len = 0;
        // SAFETY: The null output requests the required buffer size only.
        unsafe {
            GetTokenInformation(
                token.as_raw_handle(),
                TokenUser,
                ptr::null_mut(),
                0,
                &mut len,
            )
        };
        if (len as usize) < size_of::<TOKEN_USER>() || len > 65536 {
            return Err(last_error());
        }
        // Word alignment is sufficient for TOKEN_USER and its embedded SID.
        let mut info = vec![0usize; (len as usize).div_ceil(size_of::<usize>())];
        // SAFETY: info is aligned and has at least len writable bytes.
        if unsafe {
            GetTokenInformation(
                token.as_raw_handle(),
                TokenUser,
                info.as_mut_ptr().cast(),
                len,
                &mut len,
            )
        } == 0
        {
            return Err(last_error());
        }
        // SAFETY: The successful TokenUser query initialized a TOKEN_USER at info's start.
        let user = unsafe { &*info.as_ptr().cast::<TOKEN_USER>() };
        let mut sid = ptr::null_mut();
        // SAFETY: The token query supplied a valid SID; sid is writable.
        if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid) } == 0 {
            return Err(last_error());
        }
        let allocation = LocalAllocation(sid.cast());
        let mut sid_len = 0;
        // SAFETY: ConvertSidToStringSidW returns a NUL-terminated UTF-16 string.
        while unsafe { *sid.add(sid_len) } != 0 {
            sid_len += 1;
        }
        // SAFETY: The string allocation contains sid_len initialized UTF-16 units.
        let sid = String::from_utf16(unsafe { std::slice::from_raw_parts(sid, sid_len) })
            .map_err(|_| Error::Internal)?;
        drop(allocation);
        Ok(sid)
    }

    fn new(directory: bool) -> Result<Self> {
        Self::for_user(&Self::current_user_sid()?, directory)
    }

    fn for_user(sid: &str, directory: bool) -> Result<Self> {
        let flags = if directory { "OICI" } else { "" };
        Self::from_sddl(&format!("O:{sid}D:P(A;{flags};FA;;;{sid})"))
    }

    fn from_sddl(sddl: &str) -> Result<Self> {
        let sddl = wide(OsStr::new(sddl))?;
        let mut descriptor = ptr::null_mut();
        // SAFETY: sddl is NUL-terminated and descriptor is writable.
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut descriptor,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(last_error());
        }
        Ok(Self(LocalAllocation(descriptor)))
    }

    fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.0.0,
            bInheritHandle: 0,
        }
    }

    fn verify(&self, handle: &impl AsRawHandle) -> Result<()> {
        let actual =
            security_info(handle).map_err(|failure| failure.context("read_security_descriptor"))?;
        self.verify_descriptor(&actual)
    }

    fn verify_descriptor(&self, actual: &LocalAllocation) -> Result<()> {
        let (actual_owner, actual_acl) =
            descriptor_parts(actual).map_err(|failure| failure.reason("private_acl_mismatch"))?;
        let (expected_owner, expected_acl) = descriptor_parts(&self.0)?;
        // SAFETY: descriptor_parts validated both SIDs and both descriptors remain alive.
        if unsafe { EqualSid(actual_owner, expected_owner) } == 0 {
            return Err(Failure::from(Error::Permission).reason("owner_mismatch"));
        }
        if !private_acl_matches(actual_acl, expected_acl)? {
            return Err(Failure::from(Error::Permission).reason("private_acl_mismatch"));
        }
        Ok(())
    }
}

/// Compare the supported user-only ACL shape, excluding unused ACL capacity.
fn private_acl_matches(actual: &ACL, expected: &ACL) -> Result<bool> {
    fn only_allowed_ace(acl: &ACL) -> Result<Option<&ACCESS_ALLOWED_ACE>> {
        // SAFETY: Callers keep the complete ACL allocation alive for this comparison.
        if unsafe { IsValidAcl(acl) } == 0 {
            return Err(Error::Permission.into());
        }
        if acl.AceCount != 1 {
            return Ok(None);
        }
        let mut entry = ptr::null_mut();
        // SAFETY: The validated ACL contains exactly one ACE, and entry is writable.
        if unsafe { GetAce(acl, 0, &mut entry) } == 0 || entry.is_null() {
            return Err(Error::Permission.into());
        }
        // SAFETY: GetAce returned a live header inside the validated ACL.
        let header = unsafe { &*entry.cast::<ACE_HEADER>() };
        let sid_offset = offset_of!(ACCESS_ALLOWED_ACE, SidStart);
        // A SID has an eight-byte header before its variable-length subauthorities.
        if header.AceType != 0 || usize::from(header.AceSize) < sid_offset + 8 {
            return Ok(None);
        }
        // SAFETY: The ACE has the access-allowed layout and contains its fixed fields.
        let allowed = unsafe { &*entry.cast::<ACCESS_ALLOWED_ACE>() };
        let sid = ptr::from_ref(&allowed.SidStart).cast_mut().cast();
        // SAFETY: The SID header is within this ACE, including its subauthority count.
        let sid_len = 8 + usize::from(unsafe { *GetSidSubAuthorityCount(sid) }) * 4;
        if usize::from(header.AceSize) < sid_offset + sid_len {
            return Err(Error::Permission.into());
        }
        // SAFETY: The entire declared SID lies within the validated ACE.
        if unsafe { IsValidSid(sid) } == 0 {
            return Err(Error::Permission.into());
        }
        Ok(Some(allowed))
    }

    let (Some(actual), Some(expected)) = (only_allowed_ace(actual)?, only_allowed_ace(expected)?)
    else {
        return Ok(false);
    };
    if actual.Header.AceFlags != expected.Header.AceFlags || actual.Mask != expected.Mask {
        return Ok(false);
    }
    let actual_sid = ptr::from_ref(&actual.SidStart).cast_mut().cast();
    let expected_sid = ptr::from_ref(&expected.SidStart).cast_mut().cast();
    // SAFETY: Both ACEs contain validated SIDs and their descriptor allocations remain alive.
    Ok(unsafe { EqualSid(actual_sid, expected_sid) } != 0)
}

fn security_info(handle: &impl AsRawHandle) -> Result<LocalAllocation> {
    let mut descriptor = ptr::null_mut();
    // SAFETY: The handle includes READ_CONTROL access and descriptor is writable.
    let status = unsafe {
        GetSecurityInfo(
            handle.as_raw_handle(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            ptr::null_mut(),
            ptr::null_mut(),
            ptr::null_mut(),
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    if status != 0 {
        return Err(io::Error::from_raw_os_error(status as i32).into());
    }
    Ok(LocalAllocation(descriptor))
}

/// The output ACL borrows the descriptor allocation supplied by the caller.
fn descriptor_parts(allocation: &LocalAllocation) -> Result<(*mut core::ffi::c_void, &ACL)> {
    let descriptor = allocation.0;
    // SAFETY: Callers supply a live descriptor from a Windows security API.
    if descriptor.is_null() || unsafe { IsValidSecurityDescriptor(descriptor) } == 0 {
        return Err(Error::Permission.into());
    }
    let mut control = 0;
    let mut revision = 0;
    let mut owner = ptr::null_mut();
    let mut defaulted = 0;
    let mut present = 0;
    let mut acl = ptr::null_mut();
    // SAFETY: The descriptor was validated and every output points to a writable slot.
    let valid = unsafe {
        GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) != 0
            && GetSecurityDescriptorOwner(descriptor, &mut owner, &mut defaulted) != 0
            && GetSecurityDescriptorDacl(descriptor, &mut present, &mut acl, &mut defaulted) != 0
    };
    if !valid
        || owner.is_null()
        || present == 0
        || acl.is_null()
        || control & SE_DACL_PROTECTED == 0
    {
        return Err(Error::Permission.into());
    }
    // SAFETY: Both pointers came from the validated descriptor.
    if unsafe { IsValidSid(owner) == 0 || IsValidAcl(acl) == 0 } {
        return Err(Error::Permission.into());
    }
    // SAFETY: The validated ACL borrows the live descriptor allocation.
    let acl = unsafe { &*acl };
    Ok((owner, acl))
}

fn check_private_file_kind(handle: &impl AsRawHandle) -> Result<()> {
    // SAFETY: Zero is a valid initial representation of this output record.
    let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { zeroed() };
    // SAFETY: handle is valid and info is writable.
    if unsafe { GetFileInformationByHandle(handle.as_raw_handle(), &mut info) } == 0 {
        return Err(last_error());
    }
    if info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        return Err(Failure::from(Error::Permission).reason("reparse_point"));
    }
    if info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY != 0 {
        return Err(Failure::from(Error::Permission).reason("unexpected_file_type"));
    }
    if info.nNumberOfLinks != 1 {
        return Err(Failure::from(Error::Permission).reason("hard_link"));
    }
    Ok(())
}

/// A validated coordination namespace pinned for the entire acquisition attempt.
pub(crate) struct Directory {
    path: PathBuf,
    directories: Arc<Vec<File>>,
    file_security: Security,
}

/// Closing this handle releases the byte-range lock. Its pathname is never removed.
pub(crate) struct Lease {
    // Drop the ownership file before unpinning its directory namespace.
    _file: File,
    _directories: Arc<Vec<File>>,
}

pub(crate) fn prepare_directory(path: &Path) -> Result<Directory> {
    if !path.is_absolute()
        || !matches!(path.components().next(), Some(Component::Prefix(prefix))
            if matches!(prefix.kind(), Prefix::Disk(_) | Prefix::VerbatimDisk(_)))
        || path
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(Error::InvalidArgument.into());
    }
    let user = Security::current_user_sid()?;
    let security = Security::for_user(&user, true)?;
    // Pin the namespace from the drive root down. Inspect only our own leaf's
    // security: ordinary ancestor ACLs need not be private. Relative opens keep
    // creation and lookup attached to the retained handles, even during races.
    let mut ancestors: Vec<_> = path.ancestors().collect();
    ancestors.reverse();
    let root = windows_file::open_drive_root(ancestors[0])
        .map_err(|error| Failure::from(error).context("open_ancestor_directory"))?;
    let mut directories = Vec::with_capacity(ancestors.len());
    directories.push(root);
    for ancestor in ancestors.into_iter().skip(1) {
        let leaf = ancestor == path;
        let parent = directories.last().ok_or(Error::Internal)?;
        let name = ancestor.file_name().ok_or(Error::InvalidArgument)?;
        let handle = windows_file::open_at(
            parent,
            name,
            &FileOpenOptions {
                access: FILE_TRAVERSE | if leaf { READ_CONTROL } else { 0 },
                sharing: FILE_SHARE_READ | FILE_SHARE_WRITE,
                disposition: OPEN_ALWAYS,
                directory: true,
            },
        )
        .map_err(|error| Failure::from(error).context("open_ancestor_directory"))?;
        if leaf {
            security
                .verify(&handle)
                .map_err(|failure| failure.context("validate_coordination_permissions"))?;
        }
        directories.push(handle);
    }
    if directories.len() == 1 {
        // A drive root is not an application-owned coordination directory.
        return Err(Error::InvalidArgument.into());
    }
    let name = wide(path.as_os_str())?;
    let mut volume = vec![0u16; 32768];
    // SAFETY: name is NUL-terminated and volume has the declared writable capacity.
    if unsafe { GetVolumePathNameW(name.as_ptr(), volume.as_mut_ptr(), volume.len() as u32) } == 0 {
        return Err(last_error());
    }
    // SAFETY: GetVolumePathNameW initialized a NUL-terminated root pathname.
    // DRIVE_REMOVABLE=2, DRIVE_FIXED=3, DRIVE_RAMDISK=6; remote filesystem locks are excluded.
    if !matches!(unsafe { GetDriveTypeW(volume.as_ptr()) }, 2 | 3 | 6) {
        return Err(Error::Permission.into());
    }
    Ok(Directory {
        path: path.to_owned(),
        directories: Arc::new(directories),
        file_security: Security::for_user(&user, false)?,
    })
}

impl Directory {
    fn private_file(
        &self,
        path: &Path,
        access: u32,
        disposition: u32,
        share_delete: bool,
    ) -> Result<File> {
        if path.parent() != Some(self.path.as_path()) {
            return Err(Error::InvalidArgument.into());
        }
        let mut sharing = FILE_SHARE_READ | FILE_SHARE_WRITE;
        if share_delete {
            sharing |= FILE_SHARE_DELETE;
        }
        let parent = self.directories.last().ok_or(Error::Internal)?;
        let name = path.file_name().ok_or(Error::InvalidArgument)?;
        let handle = windows_file::open_at(
            parent,
            name,
            &FileOpenOptions {
                access: access | READ_CONTROL,
                sharing,
                disposition,
                directory: false,
            },
        )
        .map_err(|error| Failure::from(error).context("open_private_file"))?;
        check_private_file_kind(&handle)
            .map_err(|failure| failure.context("validate_private_file_kind"))?;
        self.file_security
            .verify(&handle)
            .map_err(|failure| failure.context("validate_private_file_permissions"))?;
        Ok(handle)
    }

    pub(crate) fn try_lock(&self, path: &Path) -> Result<Option<Lease>> {
        let file = self.private_file(path, GENERIC_READ | GENERIC_WRITE, OPEN_ALWAYS, false)?;
        // SAFETY: A zero OVERLAPPED selects the first byte of the file.
        let mut overlapped: OVERLAPPED = unsafe { zeroed() };
        // SAFETY: The synchronous handle and live OVERLAPPED are valid; FAIL_IMMEDIATELY cannot pend.
        if unsafe {
            LockFileEx(
                file.as_raw_handle(),
                LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY,
                0,
                1,
                0,
                &mut overlapped,
            )
        } == 0
        {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_LOCK_VIOLATION as i32) {
                return Ok(None);
            }
            return Err(error.into());
        }
        Ok(Some(Lease {
            _file: file,
            _directories: Arc::clone(&self.directories),
        }))
    }

    pub(crate) fn read_private(&self, path: &Path, max: usize) -> Result<Vec<u8>> {
        let file = self.private_file(path, GENERIC_READ, OPEN_EXISTING, true)?;
        let mut bytes = Vec::new();
        file.take(max as u64 + 1).read_to_end(&mut bytes)?;
        if bytes.len() > max {
            return Err(Error::Protocol.into());
        }
        Ok(bytes)
    }

    pub(crate) fn publish_private(&self, path: &Path, data: &[u8]) -> Result<()> {
        let mut nonce = [0u8; 16];
        getrandom::fill(&mut nonce).map_err(|_| Error::Internal)?;
        let temporary = path.with_extension(format!("{}.tmp", protocol::hex(&nonce)));
        let parent = self.directories.last().ok_or(Error::Internal)?;
        let name = path.file_name().ok_or(Error::InvalidArgument)?;
        let temporary_name = temporary.file_name().ok_or(Error::InvalidArgument)?;
        let result = (|| {
            let mut file =
                self.private_file(&temporary, GENERIC_WRITE | DELETE, CREATE_NEW, false)?;
            file.write_all(data)?;
            windows_file::replace_at(&file, parent, name)
                .map_err(|error| Failure::from(error).context("publish_endpoint_metadata"))?;
            Ok(())
        })();
        if result.is_err() {
            // Rename failures can be reported after publication. Remove only
            // the old staging name, never the possibly-published file handle.
            let _ = windows_file::remove_at(parent, temporary_name);
        }
        result
    }
}

fn event() -> Result<OwnedHandle> {
    // SAFETY: An unnamed non-inheritable manual-reset event needs no external pointers.
    owned(unsafe { CreateEventW(ptr::null(), 1, 0, ptr::null()) })
}

fn signaled(event: &OwnedHandle) -> bool {
    // SAFETY: event is a valid event handle; zero timeout is an immediate state check.
    unsafe { WaitForSingleObject(event.as_raw_handle(), 0) == WAIT_OBJECT_0 }
}

fn submission(result: i32) -> io::Result<()> {
    if result == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

/// Reused sequentially by one pipe worker or one activation client.
struct OperationContext {
    event: OwnedHandle,
    overlapped: Box<OVERLAPPED>,
}

impl OperationContext {
    fn new() -> Result<Self> {
        Ok(Self::with_event(event()?))
    }

    fn with_event(event: OwnedHandle) -> Self {
        // SAFETY: Zero initializes the documented reserved/offset fields.
        let mut overlapped: Box<OVERLAPPED> = Box::new(unsafe { zeroed() });
        overlapped.hEvent = event.as_raw_handle();
        Self { event, overlapped }
    }
}

/// Scoped to the I/O call so cancellation drains before its caller's buffer can be released.
struct Operation<'a> {
    pipe: &'a OwnedHandle,
    context: &'a mut OperationContext,
    pending: bool,
}

impl<'a> Operation<'a> {
    fn new(pipe: &'a OwnedHandle, context: &'a mut OperationContext) -> Result<Self> {
        // The exclusive borrow is available only after the previous guard has drained I/O.
        // SAFETY: The event is live and no operation is still using this context.
        if unsafe { ResetEvent(context.event.as_raw_handle()) } == 0 {
            return Err(last_error());
        }
        // SAFETY: The previous operation has completed; reset every field before reuse.
        *context.overlapped = unsafe { zeroed() };
        context.overlapped.hEvent = context.event.as_raw_handle();
        Ok(Self {
            pipe,
            context,
            pending: false,
        })
    }

    fn finish(
        &mut self,
        submitted: io::Result<()>,
        stop: Option<&OwnedHandle>,
        deadline: Option<Instant>,
    ) -> Result<u32> {
        if let Err(error) = submitted {
            if error.raw_os_error() != Some(ERROR_IO_PENDING as i32) {
                return Err(error.into());
            }
            self.pending = true;
            let timeout = deadline.map_or(INFINITE, |until| {
                let remaining = until.saturating_duration_since(Instant::now());
                remaining
                    .as_millis()
                    .saturating_add(u128::from(
                        !remaining.subsec_nanos().is_multiple_of(1_000_000),
                    ))
                    .min(u128::from(INFINITE - 1)) as u32
            });
            let (state, completed) = if let Some(stop) = stop {
                let handles = [stop.as_raw_handle(), self.context.overlapped.hEvent];
                // SAFETY: Both event handles remain valid throughout the bounded/idle wait.
                let state = unsafe { WaitForMultipleObjects(2, handles.as_ptr(), 0, timeout) };
                if state == WAIT_OBJECT_0 {
                    return Err(Error::Unavailable.into());
                }
                (state, WAIT_OBJECT_0 + 1)
            } else {
                // SAFETY: The operation owns this event through completion.
                (
                    unsafe { WaitForSingleObject(self.context.overlapped.hEvent, timeout) },
                    WAIT_OBJECT_0,
                )
            };
            if state == WAIT_TIMEOUT {
                return Err(Error::Timeout.into());
            }
            if state != completed {
                return Err(last_error());
            }
        }
        let mut count = 0;
        // SAFETY: The operation has completed or its event fired; the structure remains pinned.
        let success = unsafe {
            GetOverlappedResult(
                self.pipe.as_raw_handle(),
                &*self.context.overlapped,
                &mut count,
                1,
            )
        };
        self.pending = false;
        if success == 0 {
            return Err(last_error());
        }
        Ok(count)
    }
}

impl Drop for Operation<'_> {
    fn drop(&mut self) {
        if self.pending {
            // SAFETY: This operation and its caller-owned buffer are still live. Cancellation
            // is followed by completion draining before either allocation can be freed.
            unsafe {
                CancelIoEx(self.pipe.as_raw_handle(), &*self.context.overlapped);
                let mut count = 0;
                GetOverlappedResult(
                    self.pipe.as_raw_handle(),
                    &*self.context.overlapped,
                    &mut count,
                    1,
                );
            }
        }
    }
}

fn check_deadline(stop: Option<&OwnedHandle>, deadline: Instant) -> Result<()> {
    if stop.is_some_and(signaled) {
        return Err(Error::Unavailable.into());
    }
    if Instant::now() >= deadline {
        return Err(Error::Timeout.into());
    }
    Ok(())
}

fn read_some(
    pipe: &OwnedHandle,
    context: &mut OperationContext,
    buffer: &mut [u8],
    stop: Option<&OwnedHandle>,
    deadline: Instant,
) -> Result<usize> {
    check_deadline(stop, deadline)?;
    let mut operation = Operation::new(pipe, context)?;
    // SAFETY: buffer and the pinned OVERLAPPED remain alive until finish/Drop drains the I/O.
    let submitted = unsafe {
        ReadFile(
            pipe.as_raw_handle(),
            buffer.as_mut_ptr(),
            buffer.len() as u32,
            ptr::null_mut(),
            &mut *operation.context.overlapped,
        )
    };
    operation
        .finish(submission(submitted), stop, Some(deadline))
        .map(|count| count as usize)
}

fn read_exact(
    pipe: &OwnedHandle,
    context: &mut OperationContext,
    mut buffer: &mut [u8],
    stop: Option<&OwnedHandle>,
    deadline: Instant,
) -> Result<()> {
    while !buffer.is_empty() {
        let count = read_some(pipe, context, buffer, stop, deadline)?;
        if count == 0 {
            return Err(io::Error::from(io::ErrorKind::UnexpectedEof).into());
        }
        buffer = &mut buffer[count..];
    }
    Ok(())
}

fn write_all(
    pipe: &OwnedHandle,
    context: &mut OperationContext,
    mut buffer: &[u8],
    stop: Option<&OwnedHandle>,
    deadline: Instant,
) -> Result<()> {
    while !buffer.is_empty() {
        check_deadline(stop, deadline)?;
        let mut operation = Operation::new(pipe, context)?;
        // SAFETY: buffer and the pinned OVERLAPPED stay live until completion/cancellation.
        let submitted = unsafe {
            WriteFile(
                pipe.as_raw_handle(),
                buffer.as_ptr(),
                buffer.len() as u32,
                ptr::null_mut(),
                &mut *operation.context.overlapped,
            )
        };
        let count = operation.finish(submission(submitted), stop, Some(deadline))? as usize;
        if count == 0 {
            return Err(io::Error::from(io::ErrorKind::WriteZero).into());
        }
        buffer = &buffer[count..];
    }
    Ok(())
}

fn accept(pipe: &OwnedHandle, context: &mut OperationContext, stop: &OwnedHandle) -> Result<()> {
    if signaled(stop) {
        return Err(Error::Unavailable.into());
    }
    let mut operation = Operation::new(pipe, context)?;
    // SAFETY: The pipe is overlapped and the pinned OVERLAPPED stays live until drained.
    let submitted =
        unsafe { ConnectNamedPipe(pipe.as_raw_handle(), &mut *operation.context.overlapped) };
    let submitted = submission(submitted);
    let result = match submitted {
        Err(error) if error.raw_os_error() == Some(ERROR_PIPE_CONNECTED as i32) => Ok(()),
        submitted => operation.finish(submitted, Some(stop), None).map(|_| ()),
    };
    accept_result(result)
}

fn accept_result(result: Result<()>) -> Result<()> {
    match result {
        // Both immediate submission and overlapped completion can observe a vanished
        // client. Serve observes EOF, then the worker disconnects this pipe for reuse.
        Err(error)
            if matches!(error.os_code(), Some(code)
            if code == ERROR_NO_DATA as i32 || code == ERROR_BROKEN_PIPE as i32
                || code == ERROR_PIPE_NOT_CONNECTED as i32) =>
        {
            Ok(())
        }
        result => result.map_err(|error| error.context("accept_activation_pipe")),
    }
}

fn serve(
    pipe: &OwnedHandle,
    context: &mut OperationContext,
    stop: &OwnedHandle,
    token: &[u8; TOKEN_LEN],
    events: &Events,
) -> Result<()> {
    let deadline = Instant::now() + CLIENT_TIMEOUT;
    let mut request = [0; REQUEST_LEN];
    read_exact(pipe, context, &mut request, Some(stop), deadline)?;
    if !protocol::valid_request(&request, token) {
        return Err(Error::Protocol.into());
    }
    if !events.activate() {
        return Err(Error::Unavailable.into());
    }
    write_all(pipe, context, &ACK, Some(stop), deadline)?;
    // DisconnectNamedPipe discards unread data. Wait for the client to consume the ACK
    // and close, with a deadline instead of an unbounded FlushFileBuffers call.
    let _ = read_some(pipe, context, &mut [0], Some(stop), deadline);
    Ok(())
}

pub(crate) struct Server {
    endpoint: String,
    stop: Arc<OwnedHandle>,
    workers: Vec<JoinHandle<()>>,
}

impl Server {
    pub(crate) fn start(
        _runtime_dir: &Path,
        token: [u8; TOKEN_LEN],
        events: Arc<Events>,
    ) -> Result<Self> {
        let mut nonce = [0u8; 16];
        getrandom::fill(&mut nonce).map_err(|_| Error::Internal)?;
        let endpoint = format!("{PIPE_PREFIX}{}", protocol::hex(&nonce));
        let name = wide(OsStr::new(&endpoint))?;
        let security = Security::new(false)?;
        let stop = Arc::new(event()?);
        let mut pipes = Vec::with_capacity(CLIENT_LIMIT);
        // Pre-create every pipe before publication. Idle workers block indefinitely in
        // WaitForMultipleObjects, and no client can create an unbounded worker thread.
        for index in 0..CLIENT_LIMIT {
            let first = if index == 0 {
                FILE_FLAG_FIRST_PIPE_INSTANCE
            } else {
                0
            };
            // SAFETY: name/attributes remain live through creation; the handle is owned below.
            pipes.push(owned(unsafe {
                CreateNamedPipeW(
                    name.as_ptr(),
                    PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED | first,
                    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                    CLIENT_LIMIT as u32,
                    128,
                    128,
                    1000,
                    &security.attributes(),
                )
            })?);
        }
        let mut server = Self {
            endpoint,
            stop,
            workers: Vec::with_capacity(CLIENT_LIMIT),
        };
        for pipe in pipes {
            // Create each worker's fallible OS resource before advertising readiness.
            // The OVERLAPPED allocation stays on its worker thread throughout I/O.
            let operation_event = event()?;
            let stop = Arc::clone(&server.stop);
            let events = Arc::clone(&events);
            let worker = thread::Builder::new()
                .name("instance-pipe".into())
                .spawn(move || {
                    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        let mut context = OperationContext::with_event(operation_event);
                        while !signaled(&stop) && !events.is_stopped() {
                            if let Err(error) = accept(&pipe, &mut context, &stop) {
                                if !signaled(&stop) && !events.is_stopped() {
                                    return Err(error);
                                }
                                break;
                            }
                            let _ = serve(&pipe, &mut context, &stop, &token, &events);
                            // SAFETY: All I/O on this pipe has completed or been cancelled.
                            unsafe { DisconnectNamedPipe(pipe.as_raw_handle()) };
                        }
                        Ok(())
                    }));
                    let failure = match result {
                        Ok(Ok(())) => None,
                        Ok(Err(error)) => Some(error),
                        Err(_) => Some(Error::Internal.into()),
                    };
                    if let Some(error) = failure {
                        events.fail(error);
                        // SAFETY: The shared event remains valid; stop every idle sibling.
                        unsafe { SetEvent(stop.as_raw_handle()) };
                    }
                })?;
            server.workers.push(worker);
        }
        Ok(server)
    }

    pub(crate) fn endpoint(&self) -> &str {
        &self.endpoint
    }

    pub(crate) fn stop(&self) -> Result<()> {
        // SAFETY: stop is an owned manual-reset event; repeated signaling is harmless.
        if unsafe { SetEvent(self.stop.as_raw_handle()) } == 0 {
            return Err(last_error());
        }
        Ok(())
    }

    pub(crate) fn join(&mut self) -> Result<()> {
        let mut result = Ok(());
        for worker in self.workers.drain(..) {
            if worker.join().is_err() {
                result = Err(Error::Internal.into());
            }
        }
        result
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.stop();
        let _ = self.join();
    }
}

pub(crate) fn activate(endpoint: &str, token: &[u8; TOKEN_LEN], deadline: Instant) -> Result<()> {
    let suffix = endpoint.strip_prefix(PIPE_PREFIX).ok_or(Error::Protocol)?;
    if suffix.len() != 32 || !suffix.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(Error::Protocol.into());
    }
    check_deadline(None, deadline)?;
    let name = wide(OsStr::new(endpoint))?;
    // SAFETY: Only a validated local pipe name is passed; the returned handle is owned below.
    // CreateFile does not wait for busy pipes; the coordinator waits before retrying ownership.
    let pipe = owned(unsafe {
        CreateFileW(
            name.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            ptr::null(),
            OPEN_EXISTING,
            FILE_FLAG_OVERLAPPED,
            ptr::null_mut(),
        )
    })
    .map_err(|failure| failure.context("connect_pipe"))?;
    Security::new(false)?
        .verify(&pipe)
        .map_err(|failure| failure.context("validate_pipe_permissions"))?;
    let mut context = OperationContext::new()?;
    write_all(
        &pipe,
        &mut context,
        &protocol::request(token),
        None,
        deadline,
    )
    .map_err(|failure| failure.context("write_activation_request"))?;
    let mut ack = [0; ACK_LEN];
    read_exact(&pipe, &mut context, &mut ack, None, deadline)
        .map_err(|failure| failure.context("read_activation_acknowledgement"))?;
    if ack != ACK {
        return Err(Error::Protocol.into());
    }
    Ok(())
}

pub(crate) fn cleanup_endpoint(_endpoint: &str, _token: &[u8; TOKEN_LEN]) -> Result<()> {
    // Pipe names disappear when their last handle closes, including after a crash.
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, path::PathBuf, sync::mpsc};
    use windows_sys::Win32::Foundation::ERROR_PIPE_BUSY;
    use windows_sys::Win32::Storage::FileSystem::CreateDirectoryW;

    #[test]
    fn disconnected_accept_completions_are_local_but_invalid_handles_are_fatal() {
        for code in [ERROR_NO_DATA, ERROR_BROKEN_PIPE, ERROR_PIPE_NOT_CONNECTED] {
            assert_eq!(
                accept_result(Err(io::Error::from_raw_os_error(code as i32).into())),
                Ok(())
            );
        }
        let failure = accept_result(Err(io::Error::from_raw_os_error(
            windows_sys::Win32::Foundation::ERROR_INVALID_HANDLE as i32,
        )
        .into()))
        .unwrap_err();
        assert_eq!(failure.operation(), Some("accept_activation_pipe"));
    }

    #[test]
    fn clients_disappearing_around_accept_leave_all_workers_available() {
        let directory = TestDirectory::new();
        let events = Arc::new(Events::default());
        let token = [25; TOKEN_LEN];
        let server = Server::start(&directory.0, token, events.clone()).unwrap();
        for _ in 0..64 {
            drop(connect(server.endpoint()));
        }
        // All four slots, not just a surviving worker, must still accept clients.
        let mut clients = Vec::new();
        for _ in 0..CLIENT_LIMIT {
            clients.push(connect(server.endpoint()));
        }
        stop_promptly(server);
        drop(clients);
    }

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let mut nonce = [0u8; 16];
            getrandom::fill(&mut nonce).unwrap();
            let path =
                std::env::temp_dir().join(format!("keyguard-instance-{}", protocol::hex(&nonce)));
            prepare_directory(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn connect(endpoint: &str) -> OwnedHandle {
        let name = wide(OsStr::new(endpoint)).unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            // SAFETY: The test uses the local endpoint returned by its live server.
            let result = owned(unsafe {
                CreateFileW(
                    name.as_ptr(),
                    GENERIC_READ | GENERIC_WRITE,
                    0,
                    ptr::null(),
                    OPEN_EXISTING,
                    FILE_FLAG_OVERLAPPED,
                    ptr::null_mut(),
                )
            });
            match result {
                Ok(pipe) => return pipe,
                Err(error)
                    if error.os_code() == Some(ERROR_PIPE_BUSY as i32)
                        && Instant::now() < deadline =>
                {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(error) => panic!("connection failed: {error}"),
            }
        }
    }

    fn stop_promptly(mut server: Server) {
        let (send, receive) = mpsc::channel();
        thread::spawn(move || {
            server.stop().unwrap();
            send.send(server.join()).unwrap();
        });
        assert_eq!(
            receive.recv_timeout(Duration::from_millis(800)).unwrap(),
            Ok(())
        );
    }

    #[test]
    fn ownership_file_survives_release_and_cannot_be_replaced_while_locked() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let path = directory.0.join("ownership.lock");
        let first = prepared.try_lock(&path).unwrap().unwrap();
        assert!(prepared.try_lock(&path).unwrap().is_none());
        assert!(fs::remove_file(&path).is_err());
        assert!(fs::rename(&directory.0, directory.0.with_extension("moved")).is_err());
        drop(first);
        assert!(path.exists());
        assert!(prepared.try_lock(&path).unwrap().is_some());
    }

    #[test]
    fn lease_keeps_the_prepared_namespace_pinned_after_directory_drop() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let moved = directory.0.with_extension("moved");
        // Preparation pins the namespace even before ownership is acquired.
        assert!(fs::rename(&directory.0, &moved).is_err());
        let lease = prepared
            .try_lock(&directory.0.join("ownership.lock"))
            .unwrap()
            .unwrap();
        drop(prepared);
        assert!(fs::rename(&directory.0, &moved).is_err());
        drop(lease);
        fs::rename(&directory.0, &moved).unwrap();
        fs::rename(&moved, &directory.0).unwrap();
    }

    #[test]
    fn private_acl_accepts_equivalent_unused_capacity() {
        for directory in [false, true] {
            let security = Security::new(directory).unwrap();
            let (_, original) = descriptor_parts(&security.0).unwrap();
            let size = usize::from(original.AclSize);
            let expanded_size = size + 32;
            // Word alignment preserves the alignment required by Windows ACL APIs.
            let mut storage = vec![usize::MAX; expanded_size.div_ceil(size_of::<usize>())];
            // SAFETY: Both allocations contain at least size bytes, are disjoint, and the
            // copied ACL is aligned. Its extra unused capacity stays within storage.
            let expanded = unsafe {
                ptr::copy_nonoverlapping(
                    ptr::from_ref(original).cast::<u8>(),
                    storage.as_mut_ptr().cast::<u8>(),
                    size,
                );
                let acl = &mut *storage.as_mut_ptr().cast::<ACL>();
                acl.AclSize = expanded_size as u16;
                acl
            };
            assert!(private_acl_matches(expanded, original).unwrap());
        }
    }

    #[test]
    fn private_descriptor_rejects_extra_grants_wrong_owner_and_changed_flags() {
        let sid = Security::current_user_sid().unwrap();
        let expected = Security::for_user(&sid, false).unwrap();
        let rejected = [
            // Additional grants remain forbidden, even if they only permit reading.
            format!("O:{sid}D:P(A;;FA;;;{sid})(A;;GR;;;WD)"),
            format!("O:{sid}D:P(A;;FA;;;WD)"),
            format!("O:WDD:P(A;;FA;;;{sid})"),
            format!("O:{sid}D:P(A;CI;FA;;;{sid})"),
            format!("O:{sid}D:P(A;ID;FA;;;{sid})"),
            format!("O:{sid}D:P(A;;GR;;;{sid})"),
            // Matching ACEs do not excuse a DACL that can inherit new grants.
            format!("O:{sid}D:(A;;FA;;;{sid})"),
        ];
        for sddl in rejected {
            let actual = Security::from_sddl(&sddl).unwrap();
            assert_eq!(
                expected.verify_descriptor(&actual.0).unwrap_err().kind(),
                Error::Permission
            );
        }
        let expected = Security::for_user(&sid, true).unwrap();
        let missing_container_inheritance =
            Security::from_sddl(&format!("O:{sid}D:P(A;OI;FA;;;{sid})")).unwrap();
        let failure = expected
            .verify_descriptor(&missing_container_inheritance.0)
            .unwrap_err();
        assert_eq!(failure.kind(), Error::Permission);
        assert!(failure.to_string().contains("reason=private_acl_mismatch"));
    }

    #[test]
    fn coordination_rejects_an_inherited_default_dacl() {
        let directory = TestDirectory::new();
        let inherited = directory.0.join("inherited");
        fs::create_dir(&inherited).unwrap();
        assert!(
            matches!(prepare_directory(&inherited), Err(error) if error.kind() == Error::Permission)
        );
    }

    fn create_directory_with_security(path: &Path, sddl: &str) {
        let security = Security::from_sddl(sddl).unwrap();
        let name = wide(path.as_os_str()).unwrap();
        assert_ne!(
            // SAFETY: Both the path and descriptor remain live through creation.
            unsafe { CreateDirectoryW(name.as_ptr(), &security.attributes()) },
            0
        );
    }

    #[test]
    fn broad_ancestor_grants_are_accepted_but_namespace_and_leaf_remain_protected() {
        let directory = TestDirectory::new();
        let sid = Security::current_user_sid().unwrap();
        for (index, grant) in ["0x2", "GW", "FA"].into_iter().enumerate() {
            let broad = directory.0.join(format!("broad-{index}"));
            create_directory_with_security(
                &broad,
                &format!("O:{sid}D:P(A;OICI;FA;;;{sid})(A;OICI;{grant};;;WD)"),
            );
            let child = broad.join("coordination");
            let prepared = prepare_directory(&child).unwrap();
            let lease = prepared
                .try_lock(&child.join("ownership.lock"))
                .unwrap()
                .unwrap();
            let metadata = child.join("endpoint");
            prepared.publish_private(&metadata, b"private").unwrap();
            assert_eq!(prepared.read_private(&metadata, 32).unwrap(), b"private");
            // Private-file validation also proves the broad grants were not inherited.
            assert!(fs::rename(&broad, broad.with_extension("moved")).is_err());
            assert!(fs::rename(&child, child.with_extension("moved")).is_err());
            assert!(fs::remove_file(child.join("ownership.lock")).is_err());
            drop(prepared);
            assert!(fs::rename(&broad, broad.with_extension("moved")).is_err());
            drop(lease);
            let moved = broad.with_extension("moved");
            fs::rename(&broad, &moved).unwrap();
            fs::rename(&moved, &broad).unwrap();
        }
    }

    #[test]
    fn broad_coordination_directory_is_still_rejected_without_repair() {
        let directory = TestDirectory::new();
        let sid = Security::current_user_sid().unwrap();
        let broad = directory.0.join("broad");
        create_directory_with_security(&broad, &format!("O:{sid}D:P(A;OICI;FA;;;WD)"));
        let before = fs::read_dir(&broad).unwrap().count();
        let error = prepare_directory(&broad).err().unwrap();
        assert_eq!(error.kind(), Error::Permission);
        assert!(error.to_string().contains("reason=private_acl_mismatch"));
        assert_eq!(fs::read_dir(&broad).unwrap().count(), before);
        assert!(prepare_directory(&broad).is_err());
    }

    #[test]
    fn ancestors_need_neither_acl_reads_nor_listing_nor_synchronize_access() {
        use windows_sys::Win32::Security::{
            Authorization::SetNamedSecurityInfoW, PROTECTED_DACL_SECURITY_INFORMATION,
        };
        let directory = TestDirectory::new();
        let ancestor = directory.0.join("traverse-only");
        let child = ancestor.join("coordination");
        drop(prepare_directory(&child).unwrap());
        let sid = Security::current_user_sid().unwrap();
        let original = Security::for_user(&sid, true).unwrap();
        // Owner-rights ACE suppresses the owner's implicit READ_CONTROL. Keep
        // WRITE_DAC so this fixture can restore permissions before cleanup.
        let restricted =
            Security::from_sddl(&format!("O:{sid}D:P(A;;0xa0;;;{sid})(A;;WD;;;OW)")).unwrap();
        let set_dacl = |security: &Security| {
            let (_, acl) = descriptor_parts(&security.0).unwrap();
            let mut name = wide(ancestor.as_os_str()).unwrap();
            // SAFETY: The descriptor and mutable terminated name outlive the call.
            let status = unsafe {
                SetNamedSecurityInfoW(
                    name.as_mut_ptr(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::from_ref(acl).cast_mut(),
                    ptr::null(),
                )
            };
            assert_eq!(status, 0);
        };
        set_dacl(&restricted);
        let result = prepare_directory(&child);
        set_dacl(&original);
        let prepared = result.unwrap();
        let metadata = child.join("endpoint");
        prepared.publish_private(&metadata, b"private").unwrap();
        assert_eq!(prepared.read_private(&metadata, 32).unwrap(), b"private");
    }

    #[test]
    fn hard_linked_metadata_is_rejected() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let metadata = directory.0.join("endpoint");
        prepared.publish_private(&metadata, b"private").unwrap();
        fs::hard_link(&metadata, directory.0.join("alias")).unwrap();
        let failure = prepared.read_private(&metadata, 32).unwrap_err();
        assert_eq!(failure.kind(), Error::Permission);
        assert!(failure.to_string().contains("reason=hard_link"));
    }

    #[test]
    fn coordination_junction_never_creates_files_in_its_target() {
        let directory = TestDirectory::new();
        let target = directory.0.join("target");
        let link = directory.0.join("link");
        fs::create_dir(&target).unwrap();
        // Directory junctions work without the symlink privilege or Developer Mode.
        let result = std::process::Command::new("cmd.exe")
            .args(["/d", "/c", "mklink", "/J"])
            .arg(&link)
            .arg(&target)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "junction creation failed: {result:?}"
        );
        assert!(prepare_directory(&link).is_err());
        assert!(prepare_directory(&link.join("coordination")).is_err());
        assert_eq!(fs::read_dir(&target).unwrap().count(), 0);
        fs::remove_dir(&link).unwrap();
    }

    #[test]
    fn junction_inserted_after_parent_open_cannot_redirect_relative_creation() {
        use windows_sys::Win32::{
            Storage::FileSystem::{FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT},
            System::{
                IO::DeviceIoControl,
                Ioctl::{FSCTL_DELETE_REPARSE_POINT, FSCTL_SET_REPARSE_POINT},
                SystemServices::IO_REPARSE_TAG_MOUNT_POINT,
            },
        };

        let directory = TestDirectory::new();
        let parent_path = directory.0.join("parent");
        let target = directory.0.join("target");
        fs::create_dir(&target).unwrap();
        let prepared = prepare_directory(&parent_path).unwrap();
        let parent = prepared.directories.last().unwrap();
        let name = wide(parent_path.as_os_str()).unwrap();
        // The attacker gets write access to the already-open, still-empty ancestor.
        // SAFETY: The name is terminated and the returned handle is uniquely owned.
        let attacker = owned(unsafe {
            CreateFileW(
                name.as_ptr(),
                GENERIC_WRITE,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                ptr::null(),
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                ptr::null_mut(),
            )
        })
        .unwrap();
        let substitute = format!(r"\??\{}", target.display());
        let substitute: Vec<u16> = OsStr::new(&substitute).encode_wide().collect();
        // REPARSE_DATA_BUFFER: tag, data length, reserved, then mount-point
        // substitute/print offsets and lengths followed by two terminated names.
        let mut buffer = vec![
            IO_REPARSE_TAG_MOUNT_POINT as u16,
            (IO_REPARSE_TAG_MOUNT_POINT >> 16) as u16,
            (8 + (substitute.len() + 2) * 2) as u16,
            0,
            0,
            (substitute.len() * 2) as u16,
            ((substitute.len() + 1) * 2) as u16,
            0,
        ];
        buffer.extend(substitute);
        buffer.extend([0, 0]);
        let mut returned = 0;
        assert_ne!(
            // SAFETY: The word-aligned buffer contains its complete declared payload.
            unsafe {
                DeviceIoControl(
                    attacker.as_raw_handle(),
                    FSCTL_SET_REPARSE_POINT,
                    buffer.as_ptr().cast(),
                    (buffer.len() * 2) as u32,
                    ptr::null_mut(),
                    0,
                    &mut returned,
                    ptr::null_mut(),
                )
            },
            0
        );
        let result = windows_file::open_at(
            parent,
            OsStr::new("child"),
            &FileOpenOptions {
                access: FILE_TRAVERSE | READ_CONTROL,
                sharing: FILE_SHARE_READ | FILE_SHARE_WRITE,
                disposition: CREATE_NEW,
                directory: true,
            },
        );
        // Both refusing the changed root and staying on the original directory
        // capability are safe. Following its newly-inserted junction is not.
        assert!(!target.join("child").exists());
        drop(result);
        buffer[2] = 0;
        assert_ne!(
            // SAFETY: Deletion uses the eight-byte tag/length/reserved header.
            unsafe {
                DeviceIoControl(
                    attacker.as_raw_handle(),
                    FSCTL_DELETE_REPARSE_POINT,
                    buffer.as_ptr().cast(),
                    8,
                    ptr::null_mut(),
                    0,
                    &mut returned,
                    ptr::null_mut(),
                )
            },
            0
        );
    }

    #[test]
    fn relative_file_names_cannot_escape_the_coordination_directory() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let parent = prepared.directories.last().unwrap();
        for name in [
            "..",
            r"..\outside",
            "../outside",
            "endpoint:stream",
            r"C:\outside",
        ] {
            let error = windows_file::open_at(
                parent,
                OsStr::new(name),
                &FileOpenOptions {
                    access: GENERIC_WRITE,
                    sharing: FILE_SHARE_READ | FILE_SHARE_WRITE,
                    disposition: CREATE_NEW,
                    directory: false,
                },
            )
            .unwrap_err();
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        }
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 0);
    }

    #[test]
    fn metadata_is_private_atomic_and_size_bounded() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let path = directory.0.join("endpoint");
        prepared.publish_private(&path, b"first").unwrap();
        let mut old = prepared
            .private_file(&path, GENERIC_READ, OPEN_EXISTING, true)
            .unwrap();
        prepared.publish_private(&path, b"second").unwrap();
        let mut previous = Vec::new();
        old.read_to_end(&mut previous).unwrap();
        assert_eq!(previous, b"first");
        assert_eq!(prepared.read_private(&path, 6).unwrap(), b"second");
        assert_eq!(prepared.read_private(&path, 5), Err(Error::Protocol.into()));
    }

    #[test]
    fn acknowledgement_survives_reusing_pipe_instances() {
        let directory = TestDirectory::new();
        let events = Arc::new(Events::default());
        let token = [23; TOKEN_LEN];
        let server = Server::start(&directory.0, token, Arc::clone(&events)).unwrap();
        for _ in 0..32 {
            let deadline = Instant::now() + Duration::from_secs(2);
            loop {
                match activate(server.endpoint(), &token, deadline) {
                    Ok(()) => break,
                    Err(error) => {
                        assert!(Instant::now() < deadline, "activation failed: {error}");
                        thread::sleep(Duration::from_millis(5));
                    }
                }
            }
            assert_eq!(events.wait(), Ok(true));
        }
        stop_promptly(server);
    }

    #[test]
    fn operation_context_can_be_reused_after_a_read_timeout() {
        let directory = TestDirectory::new();
        let events = Arc::new(Events::default());
        let token = [23; TOKEN_LEN];
        let server = Server::start(&directory.0, token, Arc::clone(&events)).unwrap();
        let client = connect(server.endpoint());
        let mut context = OperationContext::new().unwrap();
        let mut ack = [0; ACK_LEN];

        // No request has been sent, so this read must be cancelled and drained before
        // the same event/OVERLAPPED can safely be reused for the following write/read.
        let failure = read_some(
            &client,
            &mut context,
            &mut ack,
            None,
            Instant::now() + Duration::from_millis(25),
        )
        .unwrap_err();
        assert_eq!(failure.kind(), Error::Timeout);

        let deadline = Instant::now() + CLIENT_TIMEOUT;
        write_all(
            &client,
            &mut context,
            &protocol::request(&token),
            None,
            deadline,
        )
        .unwrap();
        read_exact(&client, &mut context, &mut ack, None, deadline).unwrap();
        assert_eq!(ack, ACK);
        assert_eq!(events.wait(), Ok(true));
        drop(client);
        stop_promptly(server);
    }

    #[test]
    fn busy_pipes_time_out_then_activate_without_releasing_ownership() {
        let directory = TestDirectory::new();
        let prepared = prepare_directory(&directory.0).unwrap();
        let lock_path = directory.0.join("kgi-test.lock");
        let lease = prepared.try_lock(&lock_path).unwrap().unwrap();
        let events = Arc::new(Events::default());
        let token = [23; TOKEN_LEN];
        let server = Server::start(&directory.0, token, Arc::clone(&events)).unwrap();
        prepared
            .publish_private(
                &directory.0.join("kgi-test.endpoint"),
                &protocol::encode_endpoint(server.endpoint(), &token).unwrap(),
            )
            .unwrap();
        let mut clients: Vec<_> = (0..CLIENT_LIMIT)
            .map(|_| connect(server.endpoint()))
            .collect();

        let path = directory.0.to_str().unwrap();
        let failure = crate::acquire_or_activate(path, path, "test", 100)
            .err()
            .expect("all pipe instances are occupied");
        assert_eq!(failure.kind(), Error::Timeout);
        assert_eq!(failure.os_code(), Some(ERROR_PIPE_BUSY as i32));
        assert!(prepared.try_lock(&lock_path).unwrap().is_none());

        drop(clients.pop());
        assert!(matches!(
            crate::acquire_or_activate(path, path, "test", 2000).unwrap(),
            crate::Acquisition::Activated,
        ));
        assert_eq!(events.wait(), Ok(true));
        assert!(prepared.try_lock(&lock_path).unwrap().is_none());
        stop_promptly(server);
        drop(clients);
        drop(lease);
    }

    #[test]
    fn incorrect_token_never_queues_activation() {
        let directory = TestDirectory::new();
        let events = Arc::new(Events::default());
        let server = Server::start(&directory.0, [23; TOKEN_LEN], Arc::clone(&events)).unwrap();
        assert!(
            activate(
                server.endpoint(),
                &[24; TOKEN_LEN],
                Instant::now() + CLIENT_TIMEOUT
            )
            .is_err()
        );
        assert!(!events.state.lock().unwrap().pending);
        stop_promptly(server);
    }

    #[test]
    fn idle_connects_are_cancelled_on_stop() {
        let directory = TestDirectory::new();
        let server =
            Server::start(&directory.0, [23; TOKEN_LEN], Arc::new(Events::default())).unwrap();
        stop_promptly(server);
    }

    #[test]
    fn stalled_partial_clients_are_cancelled_on_stop() {
        let directory = TestDirectory::new();
        let server =
            Server::start(&directory.0, [23; TOKEN_LEN], Arc::new(Events::default())).unwrap();
        let mut clients = Vec::new();
        for _ in 0..CLIENT_LIMIT {
            let client = connect(server.endpoint());
            write_all(
                &client,
                &mut OperationContext::new().unwrap(),
                b"KGI1",
                None,
                Instant::now() + CLIENT_TIMEOUT,
            )
            .unwrap();
            clients.push(client);
        }
        // Each client leaves a partial fixed-size request. Reads/accepts must drain their
        // overlapped operations on shutdown, while all peer handles are still held open.
        stop_promptly(server);
        drop(clients);
    }

    #[test]
    fn endpoint_parser_never_connects_to_remote_or_arbitrary_pipes() {
        let deadline = Instant::now() + CLIENT_TIMEOUT;
        assert_eq!(
            activate(
                r"\\server\pipe\keyguard-instance-00",
                &[0; TOKEN_LEN],
                deadline
            ),
            Err(Error::Protocol.into())
        );
        assert_eq!(
            activate(r"\\.\pipe\other", &[0; TOKEN_LEN], deadline),
            Err(Error::Protocol.into())
        );
    }
}
