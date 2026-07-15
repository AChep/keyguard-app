//! POLYVAL backends

#[cfg_attr(not(target_pointer_width = "64"), path = "backend/soft32.rs")]
#[cfg_attr(target_pointer_width = "64", path = "backend/soft64.rs")]
mod soft;

use cfg_if::cfg_if;

cfg_if! {
    if #[cfg(all(target_arch = "aarch64", polyval_armv8, not(polyval_force_soft)))] {
        mod autodetect;
        mod pmull;
        pub use crate::backend::autodetect::Polyval;
    } else if #[cfg(all(
        any(target_arch = "x86_64", target_arch = "x86"),
        not(polyval_force_soft)
    ))] {
        mod autodetect;
        mod clmul;
        pub use crate::backend::autodetect::Polyval;
    } else {
        pub use crate::backend::soft::Polyval;
    }
}
