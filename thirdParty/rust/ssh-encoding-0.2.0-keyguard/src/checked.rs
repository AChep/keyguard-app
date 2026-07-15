//! Checked arithmetic helpers.

use crate::{Error, Result};

/// Extension trait for providing checked [`Iterator::sum`]-like functionality.
pub trait CheckedSum<A>: Sized {
    /// Iterate over the values of this type, computing a checked sum.
    ///
    /// Returns [`Error::Length`] on overflow.
    fn checked_sum(self) -> Result<A>;
}

impl<T> CheckedSum<usize> for T
where
    T: IntoIterator<Item = usize>,
{
    fn checked_sum(self) -> Result<usize> {
        self.into_iter()
            .try_fold(0, usize::checked_add)
            .ok_or(Error::Length)
    }
}
