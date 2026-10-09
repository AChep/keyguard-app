use super::*;
use crate::assertion::AssertionRequest;
use authenticator::{
    Assertion, ManageResult, ResetResult,
    authenticatorservice::AuthenticatorTransport,
    ctap2::{
        attestation::{AuthenticatorData, AuthenticatorDataFlags},
        server::{AuthenticatorAttachment, RpIdHash},
    },
    errors::UnsupportedOption,
};

/// Models a CTAP2-only key: a forced CTAP1 request cannot verify the user.
struct AssertionTransport {
    credential: u8,
}

impl AuthenticatorTransport for AssertionTransport {
    fn sign(
        &mut self,
        timeout: u64,
        args: SignArgs,
        _status: Sender<StatusUpdate>,
        callback: StateCallback<authenticator::Result<authenticator::SignResult>>,
    ) -> authenticator::Result<()> {
        if args.use_ctap1_fallback {
            callback.call(Err(AuthenticatorError::UnsupportedOption(
                UnsupportedOption::UserVerification,
            )));
            return Ok(());
        }
        assert_eq!(
            args.user_verification_req,
            UserVerificationRequirement::Required
        );
        assert!(args.user_presence_req);
        assert_eq!(
            args.client_data_hash,
            <[u8; 32]>::from(Sha256::digest(b"client data"))
        );
        assert_eq!(args.allow_list.len(), 2);
        assert_eq!(timeout, 60_000);
        callback.call(Ok(authenticator::SignResult {
            assertion: Assertion {
                credentials: Some(PublicKeyCredentialDescriptor {
                    id: vec![self.credential],
                    transports: vec![Transport::USB],
                }),
                auth_data: AuthenticatorData {
                    rp_id_hash: RpIdHash(Sha256::digest(args.relying_party_id.as_bytes()).into()),
                    flags: AuthenticatorDataFlags::USER_PRESENT
                        | AuthenticatorDataFlags::USER_VERIFIED,
                    counter: 1,
                    credential_data: None,
                    extensions: Default::default(),
                },
                signature: vec![0x30, 1, 2],
                user: None,
            },
            attachment: AuthenticatorAttachment::CrossPlatform,
            extensions: Default::default(),
        }));
        Ok(())
    }

    fn cancel(&mut self) -> authenticator::Result<()> {
        Ok(())
    }

    fn register(
        &mut self,
        _: u64,
        _: RegisterArgs,
        _: Sender<StatusUpdate>,
        _: StateCallback<authenticator::Result<authenticator::RegisterResult>>,
    ) -> authenticator::Result<()> {
        unreachable!("Account assertions must not register a credential")
    }

    fn reset(
        &mut self,
        _: u64,
        _: Sender<StatusUpdate>,
        _: StateCallback<authenticator::Result<ResetResult>>,
    ) -> authenticator::Result<()> {
        unreachable!("Account assertions must not reset the key")
    }

    fn set_pin(
        &mut self,
        _: u64,
        _: Pin,
        _: Sender<StatusUpdate>,
        _: StateCallback<authenticator::Result<ResetResult>>,
    ) -> authenticator::Result<()> {
        unreachable!("Account assertions must not change the PIN")
    }

    fn manage(
        &mut self,
        _: u64,
        _: Sender<StatusUpdate>,
        _: StateCallback<authenticator::Result<ManageResult>>,
    ) -> authenticator::Result<()> {
        unreachable!("Account assertions must not manage the key")
    }
}

#[test]
fn ctap2_assertion_with_required_verification_validates_the_selected_id() {
    let request = Request {
        operation: Operation::Assert,
        assertion: Some(AssertionRequest {
            rp_id: "example.com".into(),
            origin: "https://example.com".into(),
            client_data: b"client data".to_vec(),
            app_id: None,
            credentials: vec![(vec![1], 1), (vec![2], 1)],
            verification: 2,
            timeout: TIMEOUT,
        }),
        challenge: [0; 32],
        input: [0; 32],
        pin: "",
    };
    for credential in [2, 9] {
        let mut service = AuthenticatorService::new().unwrap();
        service.add_transport(Box::new(AssertionTransport { credential }));
        let result = execute_with_service(&request, &AtomicBool::new(false), &mut service);
        if credential == 2 {
            let encoded = result.unwrap();
            // AppID flag, credential length, then the selected credential ID.
            assert_eq!(&encoded[..9], &[0, 0, 0, 0, 0, 0, 0, 1, 2]);
        } else {
            assert_eq!(result, Err(Error::Protocol));
        }
    }
}
