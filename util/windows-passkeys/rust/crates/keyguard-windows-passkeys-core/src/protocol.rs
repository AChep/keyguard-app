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
/// A PRF salt or output: one HMAC-SHA-256 block.
const PRF_BYTES: usize = 32;
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

/// Reads the `prf` entry of a request's CTAP extensions map, if any. Windows sends the
/// salts that it derived from the site's inputs, so every value is [`PRF_BYTES`] long.
///
/// The format is not documented, so a value this reader does not expect never fails the
/// request: extensions that do not parse read as no PRF, and a `prf` entry whose inputs do
/// not parse as a request for PRF support without inputs. Dropping all of the inputs, not
/// only the malformed ones, keeps a credential from evaluating salts meant for another.
pub(crate) fn prf(extensions: &[u8]) -> Option<Prf> {
    if extensions.is_empty() {
        return None;
    }
    let mut reader = extensions;
    let value = ciborium::from_reader::<Value, _>(&mut reader).ok()?;
    let (Value::Map(entries), true) = (value, reader.is_empty()) else {
        return None;
    };
    let value = entry(&entries, "prf")?;
    Some(prf_inputs(value).unwrap_or_default())
}

fn prf_inputs(value: &Value) -> Option<Prf> {
    let Value::Map(prf) = value else {
        return None;
    };
    let eval = match entry(prf, "eval") {
        None => None,
        Some(values) => Some(prf_values(values)?),
    };
    let eval_by_credential = match entry(prf, "evalByCredential") {
        None => Vec::new(),
        Some(Value::Map(credentials)) if credentials.len() <= MAX_DESCRIPTORS => credentials
            .iter()
            .map(|(id, values)| match id {
                Value::Bytes(id) if !id.is_empty() && id.len() <= MAX_CREDENTIAL_ID => {
                    Some(PrfCredentialValues {
                        credential_id: id.clone(),
                        values: Some(prf_values(values)?),
                    })
                }
                _ => None,
            })
            .collect::<Option<_>>()?,
        Some(_) => return None,
    };
    Some(Prf {
        eval,
        eval_by_credential,
    })
}

fn entry<'a>(entries: &'a [(Value, Value)], key: &str) -> Option<&'a Value> {
    entries
        .iter()
        .find(|(name, _)| name.as_text() == Some(key))
        .map(|(_, value)| value)
}

fn prf_values(value: &Value) -> Option<PrfValues> {
    let Value::Map(entries) = value else {
        return None;
    };
    let salt = |key| match entry(entries, key) {
        None => Some(None),
        Some(Value::Bytes(bytes)) if bytes.len() == PRF_BYTES => Some(Some(bytes.clone())),
        Some(_) => None,
    };
    Some(PrfValues {
        first: salt("first")??,
        second: salt("second")?,
    })
}

/// The `prf` client extension output, which Windows reads from the unsigned extension
/// outputs of a response.
fn prf_output(enabled: bool, results: Option<PrfValues>) -> Result<Value, Error> {
    let mut fields = Vec::new();
    if enabled {
        fields.push((text("enabled"), Value::Bool(true)));
    }
    if let Some(PrfValues { first, second }) = results {
        if first.len() != PRF_BYTES || second.as_ref().is_some_and(|it| it.len() != PRF_BYTES) {
            return Err(Error::Invalid);
        }
        let mut values = vec![(text("first"), Value::Bytes(first))];
        if let Some(second) = second {
            values.push((text("second"), Value::Bytes(second)));
        }
        fields.push((text("results"), Value::Map(values)));
    }
    Ok(Value::Map(vec![(text("prf"), Value::Map(fields))]))
}

/// The Windows COM response is a CBOR map; the HRESULT carries operation status.
pub(crate) fn response(create: bool, response: Response) -> Result<Vec<u8>, Error> {
    let Response {
        authenticator_data,
        credential_id,
        signature,
        user_handle,
        prf_enabled,
        prf_results,
    } = response;
    if authenticator_data.len() < MIN_AUTHENTICATOR_DATA {
        return Err(Error::Invalid);
    }
    let fields = if create {
        let mut fields = vec![
            (number(1), text("none")),
            (number(2), Value::Bytes(authenticator_data)),
            (number(3), Value::Map(vec![])),
        ];
        if prf_enabled {
            // Key 6 carries the unsigned extension outputs.
            fields.push((number(6), prf_output(true, prf_results)?));
        }
        fields
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
        if prf_results.is_some() {
            // Key 8 carries the unsigned extension outputs.
            fields.push((number(8), prf_output(false, prf_results)?));
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
        // Windows forwards the PRF salts only to a plugin that lists `prf`.
        (number(2), Value::Array(vec![text("prf")])),
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
            ..Response::default()
        }
    }

    fn prf_values(first: u8, second: Option<u8>) -> PrfValues {
        PrfValues {
            first: vec![first; PRF_BYTES],
            second: second.map(|it| vec![it; PRF_BYTES]),
        }
    }

    fn prf_map(entries: Vec<(&str, Value)>) -> Value {
        Value::Map(
            entries
                .into_iter()
                .map(|(key, value)| (text(key), value))
                .collect(),
        )
    }

    fn salts(first: u8, second: Option<u8>) -> Value {
        let mut entries = vec![("first", Value::Bytes(vec![first; PRF_BYTES]))];
        if let Some(second) = second {
            entries.push(("second", Value::Bytes(vec![second; PRF_BYTES])));
        }
        prf_map(entries)
    }

    #[test]
    fn prf_reads_the_salts_and_those_of_each_credential() {
        let extensions = cbor(&prf_map(vec![(
            "prf",
            prf_map(vec![
                ("eval", salts(1, Some(2))),
                (
                    "evalByCredential",
                    Value::Map(vec![(Value::Bytes(vec![9; 16]), salts(3, None))]),
                ),
            ]),
        )]))
        .unwrap();
        assert_eq!(
            prf(&extensions),
            Some(Prf {
                eval: Some(prf_values(1, Some(2))),
                eval_by_credential: vec![PrfCredentialValues {
                    credential_id: vec![9; 16],
                    values: Some(prf_values(3, None)),
                }],
            })
        );
    }

    #[test]
    fn prf_without_inputs_is_a_request_for_support() {
        let extensions = cbor(&prf_map(vec![("prf", Value::Map(vec![]))])).unwrap();
        assert_eq!(prf(&extensions), Some(Prf::default()));
        let other = cbor(&prf_map(vec![("credProtect", number(2))])).unwrap();
        assert_eq!(prf(&other), None);
        assert_eq!(prf(&[]), None);
    }

    // The format is not documented: an unexpected value must not fail the ceremony.
    #[test]
    fn prf_ignores_inputs_that_are_not_salts() {
        let short = prf_map(vec![("first", Value::Bytes(vec![1; 31]))]);
        let long = prf_map(vec![("first", Value::Bytes(vec![1; 2 * PRF_BYTES]))]);
        let no_first = prf_map(vec![("second", Value::Bytes(vec![1; PRF_BYTES]))]);
        for eval in [short, long, no_first, text("salt"), Value::Null] {
            let extensions = cbor(&prf_map(vec![("prf", prf_map(vec![("eval", eval)]))])).unwrap();
            assert_eq!(prf(&extensions), Some(Prf::default()));
        }
        // The valid salts of eval are dropped too, so no credential evaluates them instead.
        let text_key = Value::Map(vec![(text("id"), salts(1, None))]);
        let extensions = cbor(&prf_map(vec![(
            "prf",
            prf_map(vec![
                ("eval", salts(2, None)),
                ("evalByCredential", text_key),
            ]),
        )]))
        .unwrap();
        assert_eq!(prf(&extensions), Some(Prf::default()));
        // As CTAP asks for hmac-secret at registration.
        let flag = cbor(&prf_map(vec![("prf", Value::Bool(true))])).unwrap();
        assert_eq!(prf(&flag), Some(Prf::default()));
    }

    #[test]
    fn prf_reads_extensions_that_do_not_parse_as_none() {
        let mut trailing = cbor(&prf_map(vec![("prf", Value::Map(vec![]))])).unwrap();
        trailing.push(0);
        let not_a_map = cbor(&Value::Array(vec![])).unwrap();
        for extensions in [vec![0xa1], trailing, not_a_map] {
            assert_eq!(prf(&extensions), None);
        }
    }

    #[test]
    fn registration_reports_prf_support_and_results_in_the_unsigned_outputs() {
        let response = Response {
            prf_enabled: true,
            prf_results: Some(prf_values(5, None)),
            ..assertion(None)
        };
        let bytes = super::response(true, response).unwrap();
        let Value::Map(fields) = ciborium::from_reader::<Value, _>(bytes.as_slice()).unwrap()
        else {
            panic!("not a map")
        };
        assert_eq!(
            fields.last(),
            Some(&(
                number(6),
                prf_map(vec![(
                    "prf",
                    prf_map(vec![
                        ("enabled", Value::Bool(true)),
                        ("results", salts(5, None)),
                    ]),
                )]),
            ))
        );
    }

    #[test]
    fn assertion_returns_prf_results_in_the_unsigned_outputs() {
        let response = Response {
            prf_results: Some(prf_values(5, Some(6))),
            ..assertion(None)
        };
        let bytes = super::response(false, response).unwrap();
        let Value::Map(fields) = ciborium::from_reader::<Value, _>(bytes.as_slice()).unwrap()
        else {
            panic!("not a map")
        };
        assert_eq!(
            fields.last(),
            Some(&(
                number(8),
                prf_map(vec![
                    ("prf", prf_map(vec![("results", salts(5, Some(6)))]),)
                ]),
            ))
        );
        let short = Response {
            prf_results: Some(PrfValues {
                first: vec![5; 16],
                second: None,
            }),
            ..assertion(None)
        };
        assert_eq!(super::response(false, short), Err(Error::Invalid));
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
