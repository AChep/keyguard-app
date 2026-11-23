pub(crate) fn quote_argument(value: &str) -> String {
    let mut result = String::from("\"");
    let mut backslashes = 0;
    for ch in value.chars() {
        if ch == '\\' {
            backslashes += 1;
            continue;
        }
        result.push_str(&"\\".repeat(if ch == '"' {
            backslashes * 2 + 1
        } else {
            backslashes
        }));
        result.push(ch);
        backslashes = 0;
    }
    result.push_str(&"\\".repeat(backslashes * 2));
    result.push('"');
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quotes_empty_unicode_and_trailing_slashes() {
        assert_eq!(quote_argument(""), "\"\"");
        assert_eq!(quote_argument("应用 a b"), "\"应用 a b\"");
        assert_eq!(quote_argument("a\\"), "\"a\\\\\"");
        assert_eq!(quote_argument("a\\\"b"), "\"a\\\\\\\"b\"");
        assert_eq!(quote_argument("--launch-at-login"), "\"--launch-at-login\"");
    }
}
