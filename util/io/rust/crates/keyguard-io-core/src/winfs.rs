//! Shared Windows security-descriptor helpers.
#![cfg(windows)]

use std::{ffi::OsString, io, mem::size_of, os::windows::ffi::OsStrExt, ptr};

use windows_sys::Win32::{
    Foundation::{HANDLE, LocalFree},
    Security::{
        ACL,
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            GetSecurityInfo, SE_FILE_OBJECT, SetSecurityInfo,
        },
        DACL_SECURITY_INFORMATION, EqualSid, GetSecurityDescriptorControl,
        GetSecurityDescriptorDacl, GetSecurityDescriptorLength, GetSecurityDescriptorOwner,
        GetTokenInformation, IsValidAcl, IsValidSecurityDescriptor, IsValidSid,
        OWNER_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR,
        PSID, SE_DACL_PROTECTED, SE_SELF_RELATIVE, TOKEN_INFORMATION_CLASS, TOKEN_QUERY,
        TOKEN_USER, TokenUser, UNPROTECTED_DACL_SECURITY_INFORMATION,
    },
    System::Threading::{GetCurrentProcess, OpenProcessToken},
};

#[cfg(test)]
use windows_sys::Win32::{
    Security::{ACCESS_ALLOWED_ACE, GetAce, INHERITED_ACE, TOKEN_OWNER, TokenOwner},
    Storage::FileSystem::FILE_ALL_ACCESS,
};

const SDDL_REVISION_1: u32 = 1;
const ERROR_INVALID_PARAMETER: i32 = 87;
#[cfg(test)]
const ACCESS_ALLOWED_ACE_TYPE: u8 = 0;
const DACL_PROTECTION_MASK: u16 = SE_DACL_PROTECTED;

struct OwnedWindowsHandle(HANDLE);

impl Drop for OwnedWindowsHandle {
    fn drop(&mut self) {
        // SAFETY: The wrapper uniquely owns a valid closable Win32 handle.
        let _ = unsafe { windows_sys::Win32::Foundation::CloseHandle(self.0) };
    }
}

struct OwnedLocalAllocation(*mut core::ffi::c_void);

impl Drop for OwnedLocalAllocation {
    fn drop(&mut self) {
        // SAFETY: The pointer came from a Win32 LocalAlloc-family API and
        // this wrapper owns it exactly once.
        let _ = unsafe { LocalFree(self.0) };
    }
}

/// Self-relative copy of a file DACL and its protected/unprotected state.
///
/// The descriptor is stored in word-aligned Rust-owned memory, so this type
/// owns no Win32 allocation and contains no borrowed pointers.
pub(crate) struct CapturedDacl {
    descriptor: Box<[usize]>,
    descriptor_len: usize,
    control: u16,
}

impl CapturedDacl {
    fn descriptor_ptr(&self) -> PSECURITY_DESCRIPTOR {
        self.descriptor
            .as_ptr()
            .cast::<core::ffi::c_void>()
            .cast_mut()
    }

    fn dacl(&self) -> io::Result<*mut ACL> {
        let mut present = 0;
        let mut defaulted = 0;
        let mut dacl = ptr::null_mut();
        // SAFETY: `descriptor_ptr` points to the validated self-relative
        // descriptor owned by `self`; all outputs point to writable slots.
        let read = unsafe {
            GetSecurityDescriptorDacl(
                self.descriptor_ptr(),
                &mut present,
                &mut dacl,
                &mut defaulted,
            )
        };
        if read == 0 {
            return Err(io::Error::last_os_error());
        }
        if present == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "file security descriptor has no DACL",
            ));
        }
        if !dacl.is_null() {
            // Bounds first: `IsValidAcl` dereferences, so proving the ACL lies
            // inside the captured copy has to precede any read through the
            // pointer, not follow it.
            self.validate_dacl_bounds(dacl)?;
            // SAFETY: `dacl` was obtained from a validated security
            // descriptor and is proven above to lie within `self`.
            if unsafe { IsValidAcl(dacl) } == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "file security descriptor contains an invalid DACL",
                ));
            }
        }
        Ok(dacl)
    }

    fn validate_dacl_bounds(&self, dacl: *const ACL) -> io::Result<()> {
        let base = self.descriptor.as_ptr().cast::<u8>() as usize;
        let dacl_address = dacl.cast::<u8>() as usize;
        let offset = dacl_address.checked_sub(base).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "DACL precedes its security descriptor",
            )
        })?;
        // `AclSize` is itself read through `dacl`, so the fixed ACL header has
        // to be proven inside the captured copy before it can be trusted to
        // describe the rest.
        let header_end = offset.checked_add(size_of::<ACL>()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "DACL header offset overflows")
        })?;
        if header_end > self.descriptor_len {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "DACL header extends beyond its security descriptor",
            ));
        }
        // SAFETY: `dacl` was returned by GetSecurityDescriptorDacl from a
        // descriptor that IsValidSecurityDescriptor accepted, and the fixed
        // header is proven in bounds above.
        let dacl_len = usize::from(unsafe { (*dacl).AclSize });
        let end = offset
            .checked_add(dacl_len)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "DACL length overflows"))?;
        if dacl_len < size_of::<ACL>() || end > self.descriptor_len {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "DACL extends beyond its security descriptor",
            ));
        }
        Ok(())
    }

    fn dacl_bytes(&self) -> io::Result<Option<&[u8]>> {
        let dacl = self.dacl()?;
        if dacl.is_null() {
            // A null DACL is distinct from an empty DACL: it grants access to
            // everyone and must be preserved exactly.
            return Ok(None);
        }
        // SAFETY: `dacl` was bounds-checked against the descriptor owned by
        // `self`, and the returned slice cannot outlive `self`.
        let bytes =
            unsafe { std::slice::from_raw_parts(dacl.cast::<u8>(), usize::from((*dacl).AclSize)) };
        Ok(Some(bytes))
    }

    fn equivalent_to(&self, other: &Self) -> io::Result<bool> {
        Ok(
            self.control & DACL_PROTECTION_MASK == other.control & DACL_PROTECTION_MASK
                && self.dacl_bytes()? == other.dacl_bytes()?,
        )
    }
}

/// Captures only the DACL of the file referred to by `handle`.
///
/// Owner, primary group, and SACL information are deliberately excluded.
pub(crate) fn capture_file_dacl(handle: HANDLE) -> io::Result<CapturedDacl> {
    let mut dacl = ptr::null_mut();
    let mut descriptor = ptr::null_mut();
    // SAFETY: The handle has READ_CONTROL access; unused owner, group, and
    // SACL outputs are null, and the requested outputs point to writable
    // slots. GetSecurityInfo allocates `descriptor` on the local heap.
    let status = unsafe {
        GetSecurityInfo(
            handle,
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION,
            ptr::null_mut(),
            ptr::null_mut(),
            &mut dacl,
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    let descriptor_allocation =
        (!descriptor.is_null()).then(|| OwnedLocalAllocation(descriptor.cast()));
    if status != 0 {
        return Err(io::Error::from_raw_os_error(status as i32));
    }
    let descriptor_allocation = descriptor_allocation.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned no security descriptor",
        )
    })?;
    // SAFETY: GetSecurityInfo returned a non-null descriptor allocation.
    if unsafe { IsValidSecurityDescriptor(descriptor) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned an invalid security descriptor",
        ));
    }
    // The captured copy below outlives the returned allocation, which is only
    // sound for a self-relative descriptor: an absolute one holds pointers to
    // separately allocated ACLs and SIDs that a flat memcpy does not bring
    // along, so every later dereference would read freed memory. The
    // GetSecurityInfo contract documents only that the buffer must be
    // released with LocalFree and never states which form it returns, so this
    // verifies the property instead of assuming it.
    if descriptor_control(descriptor)? & SE_SELF_RELATIVE == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned a security descriptor that is not self-relative",
        ));
    }
    let mut dacl_present = 0;
    let mut descriptor_dacl = ptr::null_mut();
    let mut dacl_defaulted = 0;
    // SAFETY: The descriptor is valid and all outputs point to writable
    // storage.
    if unsafe {
        GetSecurityDescriptorDacl(
            descriptor,
            &mut dacl_present,
            &mut descriptor_dacl,
            &mut dacl_defaulted,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    if dacl_present == 0 || descriptor_dacl != dacl {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned inconsistent DACL information",
        ));
    }

    let mut control = 0_u16;
    let mut revision = 0_u32;
    // SAFETY: The descriptor is valid and both outputs are writable.
    if unsafe { GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) } == 0 {
        return Err(io::Error::last_os_error());
    }

    // SAFETY: The descriptor was validated above.
    let descriptor_len = unsafe { GetSecurityDescriptorLength(descriptor) } as usize;
    if descriptor_len == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor has zero length",
        ));
    }
    let word_count = descriptor_len.div_ceil(size_of::<usize>());
    let mut descriptor_copy = vec![0_usize; word_count].into_boxed_slice();
    // SAFETY: Both regions contain at least `descriptor_len` bytes and do not
    // overlap. The source allocation remains alive for the copy.
    unsafe {
        ptr::copy_nonoverlapping(
            descriptor.cast::<u8>(),
            descriptor_copy.as_mut_ptr().cast::<u8>(),
            descriptor_len,
        );
    }
    drop(descriptor_allocation);

    let captured = CapturedDacl {
        descriptor: descriptor_copy,
        descriptor_len,
        control,
    };
    // Validate the copied, self-relative descriptor and its DACL before
    // returning ownership to the transaction.
    // SAFETY: `captured` owns the copied descriptor storage.
    if unsafe { IsValidSecurityDescriptor(captured.descriptor_ptr()) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "copied security descriptor is invalid",
        ));
    }
    let _ = captured.dacl()?;
    Ok(captured)
}

/// Applies a captured DACL and its protected/unprotected inheritance state.
pub(crate) fn apply_file_dacl(handle: HANDLE, metadata: &CapturedDacl) -> io::Result<()> {
    let dacl = metadata.dacl()?;
    let inheritance = if metadata.control & SE_DACL_PROTECTED != 0 {
        PROTECTED_DACL_SECURITY_INFORMATION
    } else {
        UNPROTECTED_DACL_SECURITY_INFORMATION
    };
    // SAFETY: The handle has WRITE_DAC access. `dacl` is either null (a
    // deliberate null DACL) or points into `metadata`, which outlives the
    // call. Owner, group, and SACL are explicitly not modified.
    let status = unsafe {
        SetSecurityInfo(
            handle,
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | inheritance,
            ptr::null_mut(),
            ptr::null_mut(),
            dacl,
            ptr::null(),
        )
    };
    if status == 0 {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(status as i32))
    }
}

/// Verifies exact DACL bytes and protected/unprotected state on `handle`.
///
/// `SetSecurityInfo` may add `SE_DACL_AUTO_INHERITED` while applying the
/// current inheritance model. That bookkeeping bit does not change whether
/// the DACL accepts inherited ACEs and is therefore not part of equivalence.
pub(crate) fn verify_file_dacl(handle: HANDLE, expected: &CapturedDacl) -> io::Result<()> {
    let actual = capture_file_dacl(handle)?;
    if expected.equivalent_to(&actual)? {
        Ok(())
    } else {
        Err(io::Error::other(
            "staged file DACL differs from the captured destination DACL",
        ))
    }
}

/// An owner-only security descriptor owned on the local heap.
pub(crate) struct OwnerOnlySecurity {
    descriptor: OwnedLocalAllocation,
}

/// Protected owner-only security descriptor for a directory.
///
/// Its sole allow ACE grants the current user file-all access and is marked
/// for inheritance by both child files and child directories.
pub(crate) struct OwnerOnlyDirectorySecurity {
    descriptor: OwnedLocalAllocation,
}

impl OwnerOnlyDirectorySecurity {
    pub(crate) const fn descriptor(&self) -> *const core::ffi::c_void {
        self.descriptor.0
    }
}

impl OwnerOnlySecurity {
    pub(crate) const fn descriptor(&self) -> *const core::ffi::c_void {
        self.descriptor.0
    }
}

/// Builds a descriptor granting full access only to the current process user,
/// expressed as the SDDL `O:{sid}D:P(A;;FA;;;{sid})`.
pub(crate) fn owner_only_file_security() -> io::Result<OwnerOnlySecurity> {
    let descriptor = security_descriptor_from_sddl(&owner_only_file_sddl()?)?;
    Ok(OwnerOnlySecurity { descriptor })
}

/// Builds a protected owner-only directory descriptor whose access is
/// inherited by both child files and child directories.
pub(crate) fn owner_only_directory_security() -> io::Result<OwnerOnlyDirectorySecurity> {
    let descriptor = security_descriptor_from_sddl(&owner_only_directory_sddl()?)?;
    Ok(OwnerOnlyDirectorySecurity { descriptor })
}

/// Verifies the owner and protected DACL of a newly-created owner-only
/// directory through its retained handle.
///
/// # Errors
///
/// Returns an error when the handle cannot be inspected or its owner, DACL,
/// inheritance flags, or protection state differ from `expected`.
pub(crate) fn verify_owner_only_directory(
    handle: HANDLE,
    expected: &OwnerOnlyDirectorySecurity,
) -> io::Result<()> {
    if owner_and_protected_dacl_match(handle, expected.descriptor.0.cast())? {
        Ok(())
    } else {
        Err(io::Error::other(
            "created directory owner or protected DACL differs from the requested descriptor",
        ))
    }
}

/// Verifies the owner and protected DACL of a newly-created owner-only file
/// through its retained handle.
pub(crate) fn verify_owner_only_file(
    handle: HANDLE,
    expected: &OwnerOnlySecurity,
) -> io::Result<()> {
    if owner_and_protected_dacl_match(handle, expected.descriptor.0.cast())? {
        Ok(())
    } else {
        Err(io::Error::other(
            "created file owner or protected DACL differs from the requested descriptor",
        ))
    }
}

fn owner_and_protected_dacl_match(
    handle: HANDLE,
    expected: PSECURITY_DESCRIPTOR,
) -> io::Result<bool> {
    let actual = capture_owner_dacl(handle)?;
    let expected_control = descriptor_control(expected)?;
    if expected_control & SE_DACL_PROTECTED == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "owner-only security descriptor is not DACL-protected",
        ));
    }
    owner_and_dacl_equivalent(expected, actual.0.cast())
}

/// Verifies that an object created beneath an owner-only directory inherited
/// its sole full-access ACE for the directory owner.
#[cfg(test)]
pub(crate) fn verify_inherited_owner_only_child(
    handle: HANDLE,
    expected_parent: &OwnerOnlyDirectorySecurity,
) -> io::Result<()> {
    let actual = capture_owner_dacl(handle)?;
    let actual_descriptor: PSECURITY_DESCRIPTOR = actual.0.cast();
    let expected_descriptor: PSECURITY_DESCRIPTOR = expected_parent.descriptor.0.cast();
    let expected_owner = descriptor_owner(expected_descriptor)?;
    let actual_owner = descriptor_owner(actual_descriptor)?;
    // Children created without an explicit descriptor take the token's
    // default owner. Under an elevated token that is BUILTIN\Administrators
    // rather than the user SID the parent was stamped with, so accept either.
    // SAFETY: Both SIDs belong to validated, live security descriptors.
    let owned_by_parent_owner = unsafe { EqualSid(expected_owner, actual_owner) } != 0;
    if !owned_by_parent_owner && !current_process_default_owner_matches(actual_owner)? {
        return Err(io::Error::other(
            "inherited child owner differs from both the directory owner and the process token's default owner",
        ));
    }
    if descriptor_control(actual_descriptor)? & SE_DACL_PROTECTED != 0 {
        return Err(io::Error::other(
            "inherited child DACL is unexpectedly protected",
        ));
    }

    let dacl = descriptor_dacl(actual_descriptor)?;
    // SAFETY: `descriptor_dacl` returned a validated ACL.
    if unsafe { (*dacl).AceCount } != 1 {
        return Err(io::Error::other(
            "inherited child DACL does not contain exactly one ACE",
        ));
    }
    let mut raw_ace = ptr::null_mut();
    // SAFETY: The validated ACL contains one ACE and the output is writable.
    if unsafe { GetAce(dacl, 0, &mut raw_ace) } == 0 || raw_ace.is_null() {
        return Err(io::Error::last_os_error());
    }
    let ace = raw_ace.cast::<ACCESS_ALLOWED_ACE>();
    // SAFETY: The validated ACL returned a pointer to its first ACE.
    let header = unsafe { (*ace).Header };
    if header.AceType != ACCESS_ALLOWED_ACE_TYPE
        || header.AceFlags & INHERITED_ACE as u8 == 0
        || usize::from(header.AceSize) < size_of::<ACCESS_ALLOWED_ACE>()
    {
        return Err(io::Error::other(
            "child DACL ACE is not an inherited access-allowed ACE",
        ));
    }
    // SAFETY: The ACE was validated above and has the ACCESS_ALLOWED_ACE
    // layout, including its mask and variable-length SID start.
    let (mask, ace_sid) = unsafe {
        (
            (*ace).Mask,
            ptr::from_ref(&(*ace).SidStart)
                .cast::<core::ffi::c_void>()
                .cast_mut(),
        )
    };
    if mask != FILE_ALL_ACCESS {
        return Err(io::Error::other(
            "inherited child ACE does not grant file-all access",
        ));
    }
    // SAFETY: `ace_sid` points inside the validated ACE.
    if unsafe { IsValidSid(ace_sid) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "inherited child ACE contains an invalid SID",
        ));
    }
    // SAFETY: Both SIDs are valid and live through the comparison.
    if unsafe { EqualSid(expected_owner, ace_sid) } == 0 {
        return Err(io::Error::other(
            "inherited child ACE does not grant access to the directory owner",
        ));
    }
    Ok(())
}

fn owner_only_file_sddl() -> io::Result<String> {
    let sid = current_process_user_sid()?;
    // Use the object-specific mask so the stored ACL remains byte-stable
    // across the object manager's generic-access mapping.
    Ok(format!("O:{sid}D:P(A;;FA;;;{sid})"))
}

fn owner_only_directory_sddl() -> io::Result<String> {
    let sid = current_process_user_sid()?;
    Ok(format!("O:{sid}D:P(A;OICI;FA;;;{sid})"))
}

fn security_descriptor_from_sddl(sddl: &str) -> io::Result<OwnedLocalAllocation> {
    let sddl = null_terminated_wide(OsString::from(sddl).as_os_str())?;
    let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
    // SAFETY: The SDDL is NUL-terminated and descriptor points to a writable
    // output slot. The returned allocation is owned below.
    let converted = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            ptr::null_mut(),
        )
    };
    if converted == 0 {
        return Err(io::Error::last_os_error());
    }
    if descriptor.is_null() {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }
    Ok(OwnedLocalAllocation(descriptor))
}

fn capture_owner_dacl(handle: HANDLE) -> io::Result<OwnedLocalAllocation> {
    let mut owner = ptr::null_mut();
    let mut dacl = ptr::null_mut();
    let mut descriptor = ptr::null_mut();
    // SAFETY: The handle has READ_CONTROL access; the requested owner, DACL,
    // and descriptor outputs point to writable slots. GetSecurityInfo
    // allocates `descriptor` on the local heap.
    let status = unsafe {
        GetSecurityInfo(
            handle,
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            ptr::null_mut(),
            &mut dacl,
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    let descriptor_allocation =
        (!descriptor.is_null()).then(|| OwnedLocalAllocation(descriptor.cast()));
    if status != 0 {
        return Err(io::Error::from_raw_os_error(status as i32));
    }
    let descriptor_allocation = descriptor_allocation.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned no owner/DACL descriptor",
        )
    })?;
    validate_security_descriptor(descriptor)?;
    if descriptor_owner(descriptor)? != owner || descriptor_dacl(descriptor)? != dacl {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "GetSecurityInfo returned inconsistent owner/DACL pointers",
        ));
    }
    Ok(descriptor_allocation)
}

fn owner_and_dacl_equivalent(
    expected: PSECURITY_DESCRIPTOR,
    actual: PSECURITY_DESCRIPTOR,
) -> io::Result<bool> {
    validate_security_descriptor(expected)?;
    validate_security_descriptor(actual)?;
    let expected_owner = descriptor_owner(expected)?;
    let actual_owner = descriptor_owner(actual)?;
    // SAFETY: Both owner SIDs came from validated security descriptors and
    // were individually validated by `descriptor_owner`.
    let same_owner = unsafe { EqualSid(expected_owner, actual_owner) } != 0;
    Ok(same_owner
        && descriptor_control(expected)? & DACL_PROTECTION_MASK
            == descriptor_control(actual)? & DACL_PROTECTION_MASK
        && dacl_bytes(descriptor_dacl(expected)?)? == dacl_bytes(descriptor_dacl(actual)?)?)
}

fn validate_security_descriptor(descriptor: PSECURITY_DESCRIPTOR) -> io::Result<()> {
    if descriptor.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor is null",
        ));
    }
    // SAFETY: The pointer is owned by a live Windows allocation supplied by
    // the security APIs used in this module.
    if unsafe { IsValidSecurityDescriptor(descriptor) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor is invalid",
        ));
    }
    Ok(())
}

fn descriptor_owner(descriptor: PSECURITY_DESCRIPTOR) -> io::Result<PSID> {
    let mut owner = ptr::null_mut();
    let mut defaulted = 0;
    // SAFETY: The descriptor was validated and both outputs are writable.
    if unsafe { GetSecurityDescriptorOwner(descriptor, &mut owner, &mut defaulted) } == 0 {
        return Err(io::Error::last_os_error());
    }
    if owner.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor has no owner",
        ));
    }
    // SAFETY: `owner` came from a validated security descriptor.
    if unsafe { IsValidSid(owner) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor owner SID is invalid",
        ));
    }
    Ok(owner)
}

fn descriptor_dacl(descriptor: PSECURITY_DESCRIPTOR) -> io::Result<*mut ACL> {
    let mut present = 0;
    let mut defaulted = 0;
    let mut dacl = ptr::null_mut();
    // SAFETY: The descriptor was validated and all outputs are writable.
    if unsafe { GetSecurityDescriptorDacl(descriptor, &mut present, &mut dacl, &mut defaulted) }
        == 0
    {
        return Err(io::Error::last_os_error());
    }
    if present == 0 || dacl.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor has no restrictive DACL",
        ));
    }
    // SAFETY: `dacl` came from a validated security descriptor.
    if unsafe { IsValidAcl(dacl) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor DACL is invalid",
        ));
    }
    Ok(dacl)
}

fn descriptor_control(descriptor: PSECURITY_DESCRIPTOR) -> io::Result<u16> {
    let mut control = 0_u16;
    let mut revision = 0_u32;
    // SAFETY: The descriptor was validated and both outputs are writable.
    if unsafe { GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) } == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(control)
}

fn dacl_bytes(dacl: *const ACL) -> io::Result<Vec<u8>> {
    // SAFETY: `dacl` was validated by `descriptor_dacl`.
    let length = usize::from(unsafe { (*dacl).AclSize });
    if length < size_of::<ACL>() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "security descriptor DACL is truncated",
        ));
    }
    // SAFETY: A valid ACL contains `AclSize` initialized bytes.
    Ok(unsafe { std::slice::from_raw_parts(dacl.cast::<u8>(), length) }.to_vec())
}

/// Reads one information class of the current process token into an owned
/// byte buffer of at least `min_len` bytes.
fn current_process_token_information(
    class: TOKEN_INFORMATION_CLASS,
    min_len: usize,
) -> io::Result<Vec<u8>> {
    let mut token: HANDLE = ptr::null_mut();
    // SAFETY: token points to a writable HANDLE slot and the pseudo process
    // handle remains valid for the call.
    let opened = unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) };
    if opened == 0 {
        return Err(io::Error::last_os_error());
    }
    let token = OwnedWindowsHandle(token);

    let mut required = 0_u32;
    // SAFETY: A null buffer with length zero is the documented size query.
    let _ = unsafe { GetTokenInformation(token.0, class, ptr::null_mut(), 0, &mut required) };
    if (required as usize) < min_len {
        return Err(io::Error::last_os_error());
    }
    let mut buffer = vec![0_u8; required as usize];
    // SAFETY: The vector provides `required` writable bytes and all pointer
    // arguments remain valid for the duration of the call.
    let populated = unsafe {
        GetTokenInformation(
            token.0,
            class,
            buffer.as_mut_ptr().cast(),
            required,
            &mut required,
        )
    };
    if populated == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(buffer)
}

fn current_process_user_sid() -> io::Result<String> {
    let buffer = current_process_token_information(TokenUser, size_of::<TOKEN_USER>())?;
    // SAFETY: GetTokenInformation populated at least TOKEN_USER bytes. An
    // unaligned read avoids relying on Vec<u8>'s alignment.
    let token_user = unsafe { buffer.as_ptr().cast::<TOKEN_USER>().read_unaligned() };
    if token_user.User.Sid.is_null() {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }

    let mut string_sid = ptr::null_mut();
    // SAFETY: The SID pointer refers into `buffer`, which remains alive, and
    // string_sid points to a writable PWSTR output slot.
    let converted = unsafe { ConvertSidToStringSidW(token_user.User.Sid, &mut string_sid) };
    if converted == 0 {
        return Err(io::Error::last_os_error());
    }
    if string_sid.is_null() {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }
    let string_sid_allocation = OwnedLocalAllocation(string_sid.cast());

    let mut length = 0_usize;
    // Windows SID strings are far shorter than this defensive limit.
    while length < 4096 {
        // SAFETY: ConvertSidToStringSidW returned a valid NUL-terminated
        // string. The defensive bound prevents unbounded scanning.
        if unsafe { *string_sid.add(length) } == 0 {
            break;
        }
        length += 1;
    }
    if length == 4096 {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }
    // SAFETY: The preceding scan established exactly `length` initialized
    // UTF-16 code units before the terminator.
    let sid_slice = unsafe { std::slice::from_raw_parts(string_sid, length) };
    let sid = String::from_utf16(sid_slice)
        .map_err(|_| io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER))?;
    drop(string_sid_allocation);
    Ok(sid)
}

#[cfg(test)]
fn current_process_default_owner_matches(actual_owner: PSID) -> io::Result<bool> {
    let buffer = current_process_token_information(TokenOwner, size_of::<TOKEN_OWNER>())?;
    // SAFETY: GetTokenInformation populated at least TOKEN_OWNER bytes. An
    // unaligned read avoids relying on Vec<u8>'s alignment.
    let token_owner = unsafe { buffer.as_ptr().cast::<TOKEN_OWNER>().read_unaligned() };
    if token_owner.Owner.is_null() {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }
    // SAFETY: `token_owner.Owner` points into the live token-information
    // buffer returned above.
    if unsafe { IsValidSid(token_owner.Owner) } == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "process token default owner SID is invalid",
        ));
    }

    // SAFETY: Both SIDs are validated and remain live through the comparison.
    Ok(unsafe { EqualSid(token_owner.Owner, actual_owner) } != 0)
}

fn null_terminated_wide(value: &std::ffi::OsStr) -> io::Result<Vec<u16>> {
    let mut output = value.encode_wide().collect::<Vec<_>>();
    if output.contains(&0) {
        return Err(io::Error::from_raw_os_error(ERROR_INVALID_PARAMETER));
    }
    output.push(0);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn owner_only_directory_descriptor_is_protected_and_inheritable() {
        let file = owner_only_file_sddl().expect("file descriptor SDDL must be built");
        let directory =
            owner_only_directory_sddl().expect("directory descriptor SDDL must be built");

        assert!(file.contains("D:P(A;;FA;;;"));
        assert!(!file.contains("OICI"));
        assert!(directory.contains("D:P(A;OICI;FA;;;"));
        assert_ne!(file, directory);
    }
}
