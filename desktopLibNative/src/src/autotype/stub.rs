pub(crate) fn execute(payload: &str) -> Result<(), String> {
    if payload.is_empty() {
        Ok(())
    } else {
        Err("AutoType is not supported on this platform.".to_owned())
    }
}
