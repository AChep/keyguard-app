pub(crate) fn get_system_accent_color() -> i32 {
    0
}

#[cfg(test)]
mod tests {
    use super::get_system_accent_color;

    #[test]
    fn get_system_accent_color_returns_zero_on_stub_platforms() {
        assert_eq!(0, get_system_accent_color());
    }
}
