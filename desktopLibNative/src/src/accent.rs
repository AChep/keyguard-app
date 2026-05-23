use crate::platform;

pub(crate) fn get_system_accent_color() -> i32 {
    platform::accent::get_system_accent_color()
}

#[allow(dead_code)]
pub(crate) fn opaque_argb_from_rgb(red: u8, green: u8, blue: u8) -> i32 {
    (0xFF00_0000_u32 | ((red as u32) << 16) | ((green as u32) << 8) | blue as u32) as i32
}

#[allow(dead_code)]
pub(crate) fn force_opaque_argb(argb: u32) -> i32 {
    ((argb & 0x00FF_FFFF) | 0xFF00_0000) as i32
}

#[cfg(test)]
mod tests {
    use super::{force_opaque_argb, opaque_argb_from_rgb};

    #[test]
    fn opaque_argb_from_rgb_packs_channels() {
        assert_eq!(
            0xFF33_6699_u32 as i32,
            opaque_argb_from_rgb(0x33, 0x66, 0x99),
        );
    }

    #[test]
    fn force_opaque_argb_replaces_alpha_channel() {
        assert_eq!(0xFF11_2233_u32 as i32, force_opaque_argb(0x8011_2233),);
        assert_eq!(0xFF11_2233_u32 as i32, force_opaque_argb(0x0011_2233),);
    }
}
