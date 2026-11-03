//! Bounds shared by the requests and results of every operation.
use crate::limits::MAX_VARIABLE_NAME_LENGTH;

pub const MAX_CONTENT: usize = 64 * 1024;
/// Bounds a text argument, in bytes.
pub const MAX_TEXT: usize = 4096;

/// Letters, digits, and underscores, not starting with a digit.
pub fn is_variable_name(name: &str) -> bool {
    let mut chars = name.chars();
    name.len() <= MAX_VARIABLE_NAME_LENGTH
        && chars
            .next()
            .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}
