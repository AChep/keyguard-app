//! The bridge's wire messages, and the CBOR responses Windows receives.
//!
//! [`generated`] is checked in so builds do not require `protoc`.
//! `util/windows-passkeys/schema/windows_passkeys.proto` is the source of truth, and CI
//! verifies that these declarations match it.
use crate::error::Error;
use ciborium::value::Value;

#[cfg(test)]
mod fixtures;
// Prost oneof enums store generated message values inline. The wire schema, rather than
// Rust enum layout, is the compatibility boundary.
#[allow(clippy::large_enum_variant, missing_docs)]
#[rustfmt::skip]
mod generated;

pub(crate) use generated::*;

pub(crate) const MAX_REQUEST: usize = 65_536;
pub(crate) const MAX_CREDENTIAL_ID: usize = 1023;
pub(crate) const MAX_USER_ID: usize = 64;
/// UTF-8 bytes accepted for OS text. UTF-16 never needs more units than UTF-8 bytes,
/// so every accepted string also reads back within this many units.
pub(crate) const MAX_TEXT: usize = 4096;
/// The allowed or excluded credentials of a request.
pub(crate) const MAX_DESCRIPTORS: usize = 1024;
/// The credential parameters of a registration.
pub(crate) const MAX_ALGORITHMS: usize = 128;
/// The COSE algorithm of ES256, the only one this authenticator signs with.
pub(crate) const ES256: i32 = -7;
/// The RP ID hash, flags, and signature counter that start authenticator data.
const MIN_AUTHENTICATOR_DATA: usize = 37;
pub(crate) const AAGUID: [u8; 16] = [
    0xd5, 0x48, 0x82, 0x6e, 0x79, 0xb4, 0xdb, 0x40, 0xa3, 0xd8, 0x11, 0x11, 0x6f, 0x7e, 0x83, 0x49,
];

/// The bounds of index entries. Kotlin checks entries against the same values before a sync.
pub(crate) fn limits() -> Limits {
    let bound = |value: usize| u32::try_from(value).unwrap_or(u32::MAX);
    Limits {
        credential_id_bytes: bound(MAX_CREDENTIAL_ID),
        user_id_bytes: bound(MAX_USER_ID),
        text_bytes: bound(MAX_TEXT),
    }
}

impl From<Failure> for Error {
    fn from(failure: Failure) -> Self {
        match failure {
            // Without a reason, Keyguard abandoned the ceremony.
            Failure::Unspecified => Self::Cancelled,
            Failure::Dismissed => Self::Dismissed,
            Failure::Excluded => Self::Excluded,
            Failure::NotFound => Self::NotFound,
        }
    }
}

pub(crate) fn text(value: &str) -> Value {
    Value::Text(value.into())
}
pub(crate) fn number(value: i32) -> Value {
    Value::Integer(value.into())
}

pub(crate) fn cbor(value: &Value) -> Result<Vec<u8>, Error> {
    let mut bytes = Vec::new();
    ciborium::into_writer(value, &mut bytes).map_err(|_| Error::Internal)?;
    Ok(bytes)
}

/// The Windows COM response is a CBOR map; the HRESULT carries operation status.
pub(crate) fn response(create: bool, response: Response) -> Result<Vec<u8>, Error> {
    let Response {
        authenticator_data,
        credential_id,
        signature,
        user_handle,
    } = response;
    if authenticator_data.len() < MIN_AUTHENTICATOR_DATA {
        return Err(Error::Invalid);
    }
    let fields = if create {
        vec![
            (number(1), text("none")),
            (number(2), Value::Bytes(authenticator_data)),
            (number(3), Value::Map(vec![])),
        ]
    } else {
        let credential_id = credential_id.ok_or(Error::Invalid)?;
        let signature = signature.ok_or(Error::Invalid)?;
        if credential_id.is_empty()
            || credential_id.len() > MAX_CREDENTIAL_ID
            || signature.is_empty()
        {
            return Err(Error::Invalid);
        }
        let mut fields = vec![
            (
                number(1),
                Value::Map(vec![
                    (text("id"), Value::Bytes(credential_id)),
                    (text("type"), text("public-key")),
                ]),
            ),
            (number(2), Value::Bytes(authenticator_data)),
            (number(3), Value::Bytes(signature)),
        ];
        if let Some(handle) = user_handle {
            if handle.len() > MAX_USER_ID {
                return Err(Error::Invalid);
            }
            if !handle.is_empty() {
                fields.push((
                    number(4),
                    Value::Map(vec![(text("id"), Value::Bytes(handle))]),
                ));
            }
        }
        // Like Windows' encoder, omit numberOfCredentials: CTAP2 makes it optional for one.
        fields
    };
    cbor(&Value::Map(fields))
}

pub(crate) fn authenticator_info() -> Result<Vec<u8>, Error> {
    let max_message = i32::try_from(MAX_REQUEST).map_err(|_| Error::Internal)?;
    cbor(&Value::Map(vec![
        (number(1), Value::Array(vec![text("FIDO_2_0")])),
        (number(3), Value::Bytes(AAGUID.to_vec())),
        (
            number(4),
            Value::Map(vec![
                (text("rk"), Value::Bool(true)),
                (text("up"), Value::Bool(true)),
                (text("uv"), Value::Bool(true)),
            ]),
        ),
        (number(5), number(max_message)),
        (number(9), Value::Array(vec![text("internal")])),
        (
            number(10),
            Value::Array(vec![Value::Map(vec![
                (text("alg"), number(ES256)),
                (text("type"), text("public-key")),
            ])]),
        ),
    ]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assertion(handle: Option<Vec<u8>>) -> Response {
        Response {
            authenticator_data: vec![1; 37],
            credential_id: Some(vec![2; 16]),
            signature: Some(vec![3; 70]),
            user_handle: handle,
        }
    }

    #[test]
    fn assertion_is_a_complete_integer_keyed_cbor_map() {
        let bytes = response(false, assertion(Some(vec![4; 32]))).unwrap();
        let mut reader = bytes.as_slice();
        let Value::Map(fields) = ciborium::from_reader::<Value, _>(&mut reader).unwrap() else {
            panic!("not a map")
        };
        assert!(reader.is_empty());
        assert_eq!(
            fields
                .iter()
                .map(|(key, _)| key.clone())
                .collect::<Vec<_>>(),
            (1..=4).map(number).collect::<Vec<_>>()
        );
        assert_eq!(fields[1].1, Value::Bytes(vec![1; 37]));
        assert_eq!(fields[2].1, Value::Bytes(vec![3; 70]));
    }

    #[test]
    fn missing_user_handle_is_omitted_not_fabricated() {
        for handle in [None, Some(vec![])] {
            let bytes = response(false, assertion(handle)).unwrap();
            let Value::Map(fields) = ciborium::from_reader::<Value, _>(bytes.as_slice()).unwrap()
            else {
                panic!("not a map")
            };
            assert_eq!(
                fields
                    .iter()
                    .map(|(key, _)| key.clone())
                    .collect::<Vec<_>>(),
                (1..=3).map(number).collect::<Vec<_>>()
            );
        }
        assert_eq!(
            response(false, assertion(Some(vec![4; 65]))).unwrap_err(),
            Error::Invalid
        );
    }

    #[test]
    fn registration_uses_none_attestation_and_no_signature_field() {
        let bytes = response(true, assertion(None)).unwrap();
        let mut reader = bytes.as_slice();
        let expected = Value::Map(vec![
            (number(1), text("none")),
            (number(2), Value::Bytes(vec![1; 37])),
            (number(3), Value::Map(vec![])),
        ]);
        assert_eq!(
            ciborium::from_reader::<Value, _>(&mut reader).unwrap(),
            expected
        );
        assert!(reader.is_empty());
    }
}
