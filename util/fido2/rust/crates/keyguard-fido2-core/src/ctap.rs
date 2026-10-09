use crate::{
    Error, Operation, POLL_INTERVAL, RP_ID, Request, SECRET_LENGTH, TIMEOUT, validate_auth_data,
};
use authenticator::{
    Pin, StatusPinUv, StatusUpdate,
    authenticatorservice::{AuthenticatorService, RegisterArgs, SignArgs},
    crypto::COSEAlgorithm,
    ctap2::{
        commands::client_pin::PinError,
        server::{
            AuthenticationExtensionsClientInputs, AuthenticationExtensionsPRFInputs,
            AuthenticationExtensionsPRFValues, PublicKeyCredentialDescriptor,
            PublicKeyCredentialParameters, PublicKeyCredentialUserEntity, RelyingParty,
            ResidentKeyRequirement, Transport, UserVerificationRequirement,
        },
    },
    errors::AuthenticatorError,
    statecallback::StateCallback,
};
use sha2::{Digest, Sha256};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{Receiver, RecvTimeoutError, Sender, channel},
    },
    time::Instant,
};
use zeroize::Zeroize;

#[cfg(test)]
mod assertion_tests;

pub(crate) fn execute(request: &Request, canceled: &AtomicBool) -> Result<Vec<u8>, Error> {
    let mut service = AuthenticatorService::new().map_err(map_error)?;
    service.add_u2f_usb_hid_platform_transports();
    execute_with_service(request, canceled, &mut service)
}

fn execute_with_service(
    request: &Request,
    canceled: &AtomicBool,
    service: &mut AuthenticatorService,
) -> Result<Vec<u8>, Error> {
    let (status_tx, status_rx) = channel();
    let (result_tx, result_rx) = channel();
    let timeout = request
        .assertion
        .as_ref()
        .map_or(TIMEOUT, |a| a.timeout)
        .as_millis() as u64;
    let origin = format!("https://{RP_ID}");
    let challenge = Sha256::digest(request.challenge).into();
    let start = match request.operation {
        Operation::Register => service.register(
            timeout,
            RegisterArgs {
                client_data_hash: challenge,
                relying_party: RelyingParty {
                    id: RP_ID.into(),
                    name: Some("Keyguard".into()),
                },
                origin,
                user: PublicKeyCredentialUserEntity {
                    id: request.input.to_vec(),
                    name: Some("Keyguard vault".into()),
                    display_name: None,
                },
                pub_cred_params: vec![PublicKeyCredentialParameters {
                    alg: COSEAlgorithm::ES256,
                }],
                exclude_list: vec![],
                user_verification_req: UserVerificationRequirement::Required,
                resident_key_req: ResidentKeyRequirement::Discouraged,
                extensions: prf_extension(None),
                pin: None,
                use_ctap1_fallback: false,
            },
            status_tx,
            callback(result_tx, |result: authenticator::RegisterResult| {
                if result.extensions.prf.as_ref().and_then(|p| p.enabled) != Some(true) {
                    return Err(Error::Unsupported);
                }
                let data = result.att_obj.auth_data;
                validate_auth_data(&data.rp_id_hash.0, data.flags.bits())?;
                data.credential_data
                    .map(|c| c.credential_id)
                    .ok_or(Error::Protocol)
            }),
        ),
        Operation::Assert => {
            let assertion = request
                .assertion
                .as_ref()
                .ok_or(Error::InvalidArgument)?
                .clone();
            let args = SignArgs {
                client_data_hash: Sha256::digest(&assertion.client_data).into(),
                origin: assertion.origin.clone(),
                relying_party_id: assertion.rp_id.clone(),
                allow_list: assertion
                    .credentials
                    .iter()
                    .map(|(id, _)| PublicKeyCredentialDescriptor {
                        id: id.clone(),
                        transports: vec![Transport::USB],
                    })
                    .collect(),
                user_verification_req: match assertion.verification {
                    0 => UserVerificationRequirement::Discouraged,
                    1 => UserVerificationRequirement::Preferred,
                    _ => UserVerificationRequirement::Required,
                },
                user_presence_req: true,
                extensions: AuthenticationExtensionsClientInputs {
                    app_id: assertion.app_id.clone(),
                    ..Default::default()
                },
                pin: None,
                // This flag forces CTAP1 even on FIDO2 keys. Device initialization
                // already negotiates CTAP1 for keys without CTAP2 support.
                use_ctap1_fallback: false,
            };
            service.sign(
                timeout,
                args,
                status_tx,
                callback(result_tx, move |result: authenticator::SignResult| {
                    let credential = result
                        .assertion
                        .credentials
                        .as_ref()
                        .map(|c| c.id.as_slice())
                        .ok_or(Error::Protocol)?;
                    assertion.encode_result(
                        credential,
                        &result.assertion.auth_data.to_vec(),
                        &result.assertion.signature,
                        result
                            .assertion
                            .user
                            .as_ref()
                            .map_or(&[], |u| u.id.as_slice()),
                        result.extensions.app_id.unwrap_or(false),
                    )
                }),
            )
        }
        Operation::Derive { credential } => {
            let credential = credential.to_vec();
            service.sign(
                timeout,
                SignArgs {
                    client_data_hash: challenge,
                    origin,
                    relying_party_id: RP_ID.into(),
                    allow_list: vec![PublicKeyCredentialDescriptor {
                        id: credential.clone(),
                        transports: vec![Transport::USB],
                    }],
                    user_verification_req: UserVerificationRequirement::Required,
                    user_presence_req: true,
                    extensions: prf_extension(Some(AuthenticationExtensionsPRFValues {
                        first: request.input.to_vec(),
                        second: None,
                    })),
                    pin: None,
                    use_ctap1_fallback: false,
                },
                status_tx,
                callback(result_tx, move |result: authenticator::SignResult| {
                    let mut secret = result
                        .extensions
                        .prf
                        .and_then(|p| p.results)
                        .ok_or(Error::Unsupported)?
                        .first;
                    let data = &result.assertion.auth_data;
                    let valid = validate_auth_data(&data.rp_id_hash.0, data.flags.bits()).is_ok()
                        && result
                            .assertion
                            .credentials
                            .as_ref()
                            .is_none_or(|c| c.id == credential)
                        && secret.len() == SECRET_LENGTH;
                    if valid {
                        Ok(secret)
                    } else {
                        secret.zeroize();
                        Err(Error::Protocol)
                    }
                }),
            )
        }
    };
    let result = match start {
        Ok(()) => wait(request, canceled, &status_rx, &result_rx),
        Err(error) => Err(map_error(error)),
    };
    let _ = service.cancel();
    // A successful callback may race cancellation. Clear any undelivered secret.
    while let Ok(Ok(mut bytes)) = result_rx.try_recv() {
        bytes.zeroize();
    }
    result
}

fn prf_extension(
    eval: Option<AuthenticationExtensionsPRFValues>,
) -> AuthenticationExtensionsClientInputs {
    AuthenticationExtensionsClientInputs {
        prf: Some(AuthenticationExtensionsPRFInputs {
            eval,
            ..Default::default()
        }),
        ..Default::default()
    }
}

/// Maps an authenticator result and delivers it, clearing a secret nobody receives.
fn callback<T>(
    sender: Sender<Result<Vec<u8>, Error>>,
    finish: impl FnOnce(T) -> Result<Vec<u8>, Error> + Send + 'static,
) -> StateCallback<Result<T, AuthenticatorError>> {
    StateCallback::new(Box::new(move |result: Result<T, AuthenticatorError>| {
        if let Err(error) = sender.send(result.map_err(map_error).and_then(finish))
            && let Ok(mut bytes) = error.0
        {
            bytes.zeroize();
        }
    }))
}

fn wait(
    request: &Request,
    canceled: &AtomicBool,
    status: &Receiver<StatusUpdate>,
    result: &Receiver<Result<Vec<u8>, Error>>,
) -> Result<Vec<u8>, Error> {
    let start = Instant::now();
    let mut pin_sent = false;
    loop {
        if canceled.load(Ordering::Acquire) {
            return Err(Error::Canceled);
        }
        if start.elapsed() >= request.assertion.as_ref().map_or(TIMEOUT, |a| a.timeout) {
            return Err(Error::Timeout);
        }
        while let Ok(update) = status.try_recv() {
            match update {
                StatusUpdate::PinUvError(StatusPinUv::PinRequired(sender)) => {
                    if request.pin.is_empty() {
                        return Err(Error::PinRequired);
                    }
                    if pin_sent {
                        return Err(Error::InvalidPin);
                    }
                    pin_sent = true;
                    sender
                        .send(Pin::new(request.pin))
                        .map_err(|_| Error::Canceled)?;
                }
                StatusUpdate::PinUvError(StatusPinUv::InvalidPin(..)) => {
                    return Err(Error::InvalidPin);
                }
                StatusUpdate::PinUvError(StatusPinUv::PinNotSet) => return Err(Error::PinNotSet),
                StatusUpdate::PinUvError(StatusPinUv::PinBlocked | StatusPinUv::PinAuthBlocked) => {
                    return Err(Error::PinBlocked);
                }
                StatusUpdate::PinUvError(StatusPinUv::UvBlocked) => {
                    // authenticator-rs falls back to PIN when built-in verification is blocked.
                }
                StatusUpdate::PinUvError(
                    StatusPinUv::PinIsTooShort | StatusPinUv::PinIsTooLong(_),
                ) => return Err(Error::InvalidPin),
                StatusUpdate::SelectResultNotice(sender, users) => {
                    if request.assertion.is_some() && !users.is_empty() {
                        // All allowed credentials authenticate the same account. The returned
                        // credential ID is checked against that allow list before leaving the bridge.
                        let _ = sender.send(Some(0));
                    } else {
                        let _ = sender.send(None);
                        return Err(Error::Protocol);
                    }
                }
                _ => {}
            }
        }
        match result.recv_timeout(POLL_INTERVAL) {
            Ok(result) => return result,
            Err(RecvTimeoutError::Disconnected) => return Err(Error::Internal),
            Err(RecvTimeoutError::Timeout) => {}
        }
    }
}

fn map_error(error: AuthenticatorError) -> Error {
    match error {
        AuthenticatorError::CancelledByUser => Error::Canceled,
        // Completion can arrive before wait() processes the corresponding status update.
        AuthenticatorError::PinError(PinError::PinRequired) => Error::PinRequired,
        AuthenticatorError::PinError(
            PinError::InvalidPin(_) | PinError::PinIsTooShort | PinError::PinIsTooLong(_),
        ) => Error::InvalidPin,
        AuthenticatorError::PinError(PinError::PinBlocked | PinError::PinAuthBlocked) => {
            Error::PinBlocked
        }
        AuthenticatorError::PinError(PinError::PinNotSet) => Error::PinNotSet,
        AuthenticatorError::UnsupportedOption(_) | AuthenticatorError::NoConfiguredTransports => {
            Error::Unsupported
        }
        _ => Error::Rejected,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn registration_request(pin: &str) -> Vec<u8> {
        let mut bytes = vec![0; crate::HEADER_LENGTH];
        bytes[0] = 1;
        bytes[3] = pin.len() as u8;
        bytes.extend_from_slice(pin.as_bytes());
        bytes
    }

    #[test]
    fn blocked_biometrics_fall_back_to_pin_prompt() {
        let (status_tx, status_rx) = channel();
        let (_result_tx, result_rx) = channel();
        let (pin_tx, _pin_rx) = channel();
        let bytes = registration_request("");
        let request = Request::parse(&bytes).unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::UvBlocked))
            .unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::PinRequired(pin_tx)))
            .unwrap();
        assert_eq!(
            wait(&request, &AtomicBool::new(false), &status_rx, &result_rx),
            Err(Error::PinRequired)
        );
    }

    #[test]
    fn blocked_biometrics_allow_submitted_pin() {
        let (status_tx, status_rx) = channel();
        let (result_tx, result_rx) = channel();
        let (pin_tx, pin_rx) = channel();
        let bytes = registration_request("1234");
        let request = Request::parse(&bytes).unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::UvBlocked))
            .unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::PinRequired(pin_tx)))
            .unwrap();
        result_tx.send(Ok(vec![42; SECRET_LENGTH])).unwrap();
        assert_eq!(
            wait(&request, &AtomicBool::new(false), &status_rx, &result_rx),
            Ok(vec![42; SECRET_LENGTH])
        );
        assert_eq!(pin_rx.try_recv().unwrap().as_bytes(), b"1234");
    }

    #[test]
    fn blocked_pin_does_not_submit_pin() {
        for status in [StatusPinUv::PinBlocked, StatusPinUv::PinAuthBlocked] {
            let (status_tx, status_rx) = channel();
            let (_result_tx, result_rx) = channel();
            let (pin_tx, pin_rx) = channel();
            let bytes = registration_request("1234");
            let request = Request::parse(&bytes).unwrap();
            status_tx.send(StatusUpdate::PinUvError(status)).unwrap();
            status_tx
                .send(StatusUpdate::PinUvError(StatusPinUv::PinRequired(pin_tx)))
                .unwrap();
            assert_eq!(
                wait(&request, &AtomicBool::new(false), &status_rx, &result_rx),
                Err(Error::PinBlocked)
            );
            assert_eq!(pin_rx.try_iter().count(), 0);
        }
    }

    #[test]
    fn callback_preserves_pin_errors_without_status_notifications() {
        for (pin_error, expected) in [
            (PinError::PinNotSet, Error::PinNotSet),
            (PinError::PinBlocked, Error::PinBlocked),
            (PinError::PinAuthBlocked, Error::PinBlocked),
            (PinError::PinRequired, Error::PinRequired),
            (PinError::InvalidPin(Some(7)), Error::InvalidPin),
            (PinError::PinIsTooShort, Error::InvalidPin),
            (PinError::PinIsTooLong(64), Error::InvalidPin),
        ] {
            let (_status_tx, status_rx) = channel();
            let (result_tx, result_rx) = channel();
            let bytes = registration_request("");
            let request = Request::parse(&bytes).unwrap();
            // The result can wake the waiter before it processes the matching status.
            callback(result_tx, |_: ()| Ok(vec![]))
                .call(Err(AuthenticatorError::PinError(pin_error)));
            assert_eq!(
                wait(&request, &AtomicBool::new(false), &status_rx, &result_rx),
                Err(expected)
            );
        }
    }

    #[test]
    fn invalid_pin_does_not_retry_without_user_input() {
        let (status_tx, status_rx) = channel();
        let (_result_tx, result_rx) = channel();
        let (pin_tx, pin_rx) = channel();
        let bytes = registration_request("1234");
        let request = Request::parse(&bytes).unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::PinRequired(
                pin_tx.clone(),
            )))
            .unwrap();
        status_tx
            .send(StatusUpdate::PinUvError(StatusPinUv::InvalidPin(
                pin_tx,
                Some(7),
            )))
            .unwrap();
        assert_eq!(
            wait(&request, &AtomicBool::new(false), &status_rx, &result_rx),
            Err(Error::InvalidPin)
        );
        assert_eq!(pin_rx.try_iter().count(), 1);
    }
}
