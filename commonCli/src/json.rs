//! JSON that can hold secret values, wiped when dropped.
use serde_json::Value;
use zeroize::Zeroize;

pub fn wipe_json(value: &mut Value) {
    match value {
        Value::String(value) => value.zeroize(),
        Value::Object(values) => values.values_mut().for_each(wipe_json),
        Value::Array(values) => values.iter_mut().for_each(wipe_json),
        _ => {}
    }
}

pub struct PrivateJson(pub Value);
impl Drop for PrivateJson {
    fn drop(&mut self) {
        wipe_json(&mut self.0);
    }
}
