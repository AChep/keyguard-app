use super::force_opaque_argb;

type Bool = i32;
type Dword = u32;
type Hresult = i32;

const S_OK: Hresult = 0;

#[link(name = "dwmapi")]
unsafe extern "system" {
    fn DwmGetColorizationColor(pcrColorization: *mut Dword, pfOpaqueBlend: *mut Bool) -> Hresult;
}

pub(crate) fn get_system_accent_color() -> i32 {
    let mut colorization = 0_u32;
    let mut opaque_blend = 0;
    // SAFETY: Both out-pointers refer to initialized, correctly aligned local
    // values that remain live and exclusively borrowed for the duration of the
    // system call. DwmGetColorizationColor does not retain them.
    let result = unsafe { DwmGetColorizationColor(&mut colorization, &mut opaque_blend) };
    if result != S_OK {
        return 0;
    }

    force_opaque_argb(colorization)
}
