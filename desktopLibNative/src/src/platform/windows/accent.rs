use crate::accent;

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
    let result = unsafe { DwmGetColorizationColor(&mut colorization, &mut opaque_blend) };
    if result != S_OK {
        return 0;
    }

    accent::force_opaque_argb(colorization)
}
