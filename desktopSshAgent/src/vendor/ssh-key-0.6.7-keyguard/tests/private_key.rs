//! SSH private key tests.

use hex_literal::hex;
use ssh_key::{Algorithm, Cipher, KdfAlg, PrivateKey};

#[cfg(feature = "ecdsa")]
use ssh_key::EcdsaCurve;

#[cfg(all(feature = "alloc"))]
use ssh_key::LineEnding;

#[cfg(all(feature = "std"))]
use {
    ssh_key::PublicKey,
    std::{io, path::PathBuf, process},
};

/// DSA OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_DSA_EXAMPLE: &str = include_str!("examples/id_dsa_1024");

/// ECDSA/P-256 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P256_EXAMPLE: &str = include_str!("examples/id_ecdsa_p256");

/// ECDSA/P-384 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P384_EXAMPLE: &str = include_str!("examples/id_ecdsa_p384");

/// ECDSA/P-521 OpenSSH-formatted public key
#[cfg(feature = "ecdsa")]
const OPENSSH_ECDSA_P521_EXAMPLE: &str = include_str!("examples/id_ecdsa_p521");

/// Ed25519 OpenSSH-formatted private key
const OPENSSH_ED25519_EXAMPLE: &str = include_str!("examples/id_ed25519");

/// RSA (3072-bit) OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_RSA_3072_EXAMPLE: &str = include_str!("examples/id_rsa_3072");

/// RSA (4096-bit) OpenSSH-formatted public key
#[cfg(feature = "alloc")]
const OPENSSH_RSA_4096_EXAMPLE: &str = include_str!("examples/id_rsa_4096");

/// OpenSSH-formatted private key with a custom algorithm name
#[cfg(feature = "alloc")]
const OPENSSH_OPAQUE_EXAMPLE: &str = include_str!("examples/id_opaque");

/// Get a path into the `tests/scratch` directory.
#[cfg(feature = "std")]
pub fn scratch_path(filename: &str) -> PathBuf {
    PathBuf::from(&format!("tests/scratch/{}", filename))
}

#[cfg(feature = "alloc")]
#[test]
fn decode_dsa_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_DSA_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Dsa, key.algorithm());
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let dsa_keypair = key.key_data().dsa().unwrap();
    assert_eq!(
        &hex!(
            "00dc3d89250ed9462114cb2c8d4816e3a511aaff1b06b0e01de17c1cb04e581bcab97176471d89fd7ca1817
             e3c48e2ccbafd2170f69e8e5c8b6ab69b9c5f45d95e1d9293e965227eee5b879b1123371c21b1db60f14b5e
             5c05a4782ceb43a32f449647703063621e7a286bec95b16726c18b5e52383d00b297a6b03489b06068a5"
        ),
        dsa_keypair.public.p.as_bytes(),
    );
    assert_eq!(
        &hex!("00891815378597fe42d3fd261fe76df365845bbb87"),
        dsa_keypair.public.q.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "4739b3908a8415466dc7b156fb98ecb71552a170ba0b3b7aa81bd81391de0a7ae7a1b45002dfeadc9225fbc
             520a713fe4104a74bed53fd5915da736365afd3f09777bbccfbadf7ac2b087b7f4d95fabe47d72a46e95088
             f9cd2a9fbf236b58a6982647f3c00430ad7352d47a25ebbe9477f0c3127da86ad7448644b76de5875c"
        ),
        dsa_keypair.public.g.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "6042a6b3fd861344cb21ccccd8719e25aa0be0980e79cbabf4877f5ef071f6039770352eac3d4c368f29daf
             a57b475c78d44989f16577527e598334be6aae4abd750c36af80489d392697c1f32f3cf3c9a8b99bcddb53d
             7a37e1a28fd53d4934131cf41c437c6734d1e04004adcd925b84b3956c30c3a3904eecb31400b0df48"
        ),
        dsa_keypair.public.y.as_bytes(),
    );
    assert_eq!(
        &hex!("0c377ac449e770d89a3557743cbd050396114b62"),
        dsa_keypair.private.as_bytes()
    );
    assert_eq!("user@example.com", key.comment());
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p256_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_ECDSA_P256_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP256
        },
        key.algorithm(),
    );
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let ecdsa_keypair = key.key_data().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP256, ecdsa_keypair.curve());
    assert_eq!(
        &hex!(
            "047c1fd8730ce53457be8d924098ec3648830f92aa8a2363ac656fdd4521fa6313e511f1891b4e9e5aaf8e1
             42d06ad15a66a4257f3f051d84e8a0e2f91ba807047"
        ),
        ecdsa_keypair.public_key_bytes(),
    );
    assert_eq!(
        &hex!("ca78a64774bfae37123224937f0398960189707aca0a8645ceb4359c423ba079"),
        ecdsa_keypair.private_key_bytes(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p384_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_ECDSA_P384_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP384
        },
        key.algorithm()
    );
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let ecdsa_keypair = key.key_data().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP384, ecdsa_keypair.curve());
    assert_eq!(
        &hex!(
            "042e6e82dc5407f104a11117c7c05b1993c3ceb3db25fae68ba169502a4ff9395d9ad36b543e8014ff15d70
             8e21f09f585aa6dfad575b79b943418b86198d9bcd9b07fff9399b15d43d34efaeb2e56b7b33cff880b242b
             3e0b58af96c75841ec41"
        ),
        ecdsa_keypair.public_key_bytes(),
    );
    assert_eq!(
        &hex!(
            "0377d9e9328b2925196977320a2bfe013801897fa0287848af817bdc7f400e8801fd0f9c057d106914b389c
             b156f600b"
        ),
        ecdsa_keypair.private_key_bytes(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());
}

#[cfg(feature = "ecdsa")]
#[test]
fn decode_ecdsa_p521_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_ECDSA_P521_EXAMPLE).unwrap();
    assert_eq!(
        Algorithm::Ecdsa {
            curve: EcdsaCurve::NistP521
        },
        key.algorithm()
    );
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let ecdsa_keypair = key.key_data().ecdsa().unwrap();
    assert_eq!(EcdsaCurve::NistP521, ecdsa_keypair.curve());
    assert_eq!(
        &hex!(
            "04016136934f192b23d961fbf44c8184166002cea2c7d18b20ad018d046ef068d3e8250fd4e9f17ca6693a8
             554c3269a6d9f5762a2f9a2cb8797d4b201de421d3dcc580103cb947a858bb7783df863f82951d96f91a792
             5d7e2baad26e47e3f2fa5b07c8272848a4423b750d7ad2b8b692d66ddecaec5385086b1fd1b682ca291c88d
             63762"
        ),
        ecdsa_keypair.public_key_bytes(),
    );
    assert_eq!(
        &hex!(
            "01ec905f2ab7a9169f161f09e567fcab225bbe6276727a5f2724535c2b663d7ad8e32527d7f5998a992240c
             bb90cec3ed67fe902bced588beb972c7716e0927cda82"
        ),
        ecdsa_keypair.private_key_bytes(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!("user@example.com", key.comment());
}

#[test]
fn decode_ed25519_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_ED25519_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Ed25519, key.algorithm());
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let ed25519_keypair = key.key_data().ed25519().unwrap();
    assert_eq!(
        &hex!("b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62"),
        ed25519_keypair.public.as_ref(),
    );
    assert_eq!(
        &hex!("b606c222d10c16dae16c70a4d45173472ec617e05c656920d26e56c08fb591ed"),
        ed25519_keypair.private.as_ref(),
    );

    #[cfg(feature = "alloc")]
    assert_eq!(key.comment(), "user@example.com");
}

#[cfg(feature = "alloc")]
#[test]
fn decode_rsa_3072_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_RSA_3072_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Rsa { hash: None }, key.algorithm());
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let rsa_keypair = key.key_data().rsa().unwrap();
    assert_eq!(&hex!("010001"), rsa_keypair.public.e.as_bytes());
    assert_eq!(
        &hex!(
            "00a68e478c9bc93726436b7f5e9e6f9a46e1b73bec1e8cb7754de2c6a5b6c455f2f012a7259afcf94181d69
             e95d39a349e4d2b482a5372b28943731db75c73ce7bd9eec85010c94bfae56960118922f86a8b3655b357d2
             4e7a679cd8a7d9bf6eae66f7f9a56fe3d090d0632218a682960d8aad93c01898780ead2dbefd70fb4703471
             7e412e4fdae685292ec891e2423f7fe43df2f54329ab0a5d7561e582e42e86ebaee0c1e9eaf603d7ce70850
             5d0ee090912e1fc3735eb5804ddf42b6133107a76e9a59cdfc6b65f43c6302cfbca8e7aa6f97457fa96d3b5
             a26e8f41204d2cd42be119c684b0f02370899a71ae3c1e71331543cc3fb2b4268780011ae4ea934c0ff0770
             8ee183e7e906fee489e8e1e57fce7a1c6df8fbaef39bbd1955dbd5ad1abffbe126f50205cb884af080ff3d7
             0549d3174b85bd7f6624c3753cf235b650d0e4228f32be7b54a590d869fb7786559bb7a4d66f9d3a69c085e
             fdf083a915d47a1d9161a08756b263b06e739d99f2890362abc96ade42cce8f939a40daff9"
        ),
        rsa_keypair.public.n.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "6b630b10c6950ac0d9f16273103626c392dec07cf2098a73d09ee9b388ceb817e5e030f2d7264a538932669
             775925460c8a2a269dfd9f0f0fd932852c4024adca1dc0a3d4d456c7ebd119f064f6443c4f633373865e44c
             0331f0f7e3e94a3b43a952331d0eb2551439b7e11101b2eaaa9a8265e41237a418da61c765c345d03875cb1
             a9b70177c2ef9268fe9ac8c62c08fa9152a7fe00ccade72a3acf6f004e5b617424a8027922dbc175f228626
             29e4727198ca940b3bc24c9268e3ab5f5e5965b8387e1470679b3ea680965f81593cb141310d4c41569e25b
             376a2dc4ed92f9149b75e970e8224730946cc20f351f8115b1bbfcbb38f6bd06c620472b76d641d35802ade
             f15220ab9b2a57e35918fe27e138119cca56a98ccf3c0fc683a19f2721a8766ecdd9c57677662656f3d3e05
             325f3fb37326623dbbec63b3d984830b2dd27bebb6bd2ed5345dfff18df1806adebceda9845804968930681
             ac3e523138c5216cb135997e3e143a7816acc3d8741eacec7e15f53da0f0810691708d9d"
        ),
        rsa_keypair.private.d.as_bytes()
    );
    assert_eq!(
        &hex!(
            "54405aa663ae9e080035a71002f4c351d7d002c35200725e6dc0ff6d901a304b103a2ea016507996d319358
             b5bea4feb4098f530528f12b98c50c7ddfc85fc4af9257baf9aa7235cdf4677349b77e52a40a5e2f44122d1
             8ce40941914e99169c48b708898d29869656607b7e2eb63541e0bbd568e4f774bec3137d02a591c8c77b2e1
             88dd6f72eef5cff1927cde573a4ca0d43c2b6c6a95721445122e1cf6aa5f05b65cb9c86124f9f79fa29f05e
             3f06f3b83edca9941f571650e0fb468aae4c"
        ),
        rsa_keypair.private.iqmp.as_bytes()
    );
    assert_eq!(
        &hex!(
            "00d11e7ae248640d3b20293691886544cdfe0779a9aab56c8f9626bf1e6648bfd988bdb0ac7d11a73e34056
             336d4ad3900ff7184ee404b84baeb11a2a302f982d94eefb757030ec6d77d9115eaef80ab5749a0f5b590b2
             99ffde94f9930dedbab2af333095b08a504b9b30f1112dce51f37ed00d043343d420d2e2fb88bd7f881b7c8
             990e48b93fd1d284fcc8c1c07fbefc04d02925b2f159a9a9f567073e1c94fdc6e472f48963be16c5c545385
             6ad2bf7916e42c36e75a5018910d8dad038d73"
        ),
        rsa_keypair.private.p.as_bytes()
    );
    assert_eq!(
        &hex!(
            "00cbe50e7ecdf5e2c86e76ca94be8b0c05b09041d2bcddeeb4672e7120eacc8380e5d6b77c85583c508f8ee
             8d557cabcb6dfae8c72385f0f69345ebc15a5dfec949c0770d02d7945dd3592562da7388662e053c5454d26
             90de2a63cc555e1a010e2da0ae307d1c11f852a9c7568e02f93d49b2a8f927b79943b2920cad321aa208410
             9d136d085c1d05c39f4c36cf89fdc3c66a755e63f446e16302b13599400f0a83321a2e6b9153df02f03de31
             8ea09039282853f2011e0905d1157667caf1e3"
        ),
        rsa_keypair.private.q.as_bytes()
    );
    assert_eq!("user@example.com", key.comment());
}

#[cfg(feature = "alloc")]
#[test]
fn decode_rsa_4096_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_RSA_4096_EXAMPLE).unwrap();
    assert_eq!(Algorithm::Rsa { hash: None }, key.algorithm());
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let rsa_keypair = key.key_data().rsa().unwrap();
    assert_eq!(&hex!("010001"), rsa_keypair.public.e.as_bytes());
    assert_eq!(
        &hex!(
            "00b45911edc6ec5e7d2261a48c46ab889b1858306271123e6f02dc914cf3c0352492e8a6b7a7925added527
             e547dcebff6d0c19c0bc9153975199f47f4964ed20f5aceed4e82556b228a0c1fbfaa85e6339ba2ff4094d9
             4e2b09d43a3dd68225d0bbc858293cbf167b18d6374ebe79220a633d400176f1f6b46fd626acb252bf294aa
             db2acd59626a023a8e5ec53ced8685164c72ca3a2ec646812c6e61ffcba740ff15c054f0691e3a8d52c79c4
             4b7c1fc6c9704aed09ee0195bf09c5c5ba1173b7b1179be33fb3711d3b82e98f80521367a84303cb1236ebe
             8fc095683420a4de652c071d592759d42a0c9d2e73313cdfb71a071c936659433481a406308820e173b934f
             be877d873fec24d31a4d3bb9a3645055ca37bf710e214e5fc250d5964c66f18e4f05a3b93f42aa0753bd044
             e45b456c0e62fdcc1fcadef72930dc8a7a96b3e27d8eecea139a00aaf2fe79063ccb78d26d537625bdf0c4c
             8a68a04ed6f965eef7a6b1da5d8e26fc57f1047b97e2c594a9e420410977f22d1751b6d9498e8e457034049
             3c336bf86563ef03a15bc49b0ba6fe73201f64f0413ddb4d0cc5f6cf43389907e1df29e0cc388040e3371d0
             4814140f75cac08079431043222fb91f075d76be55cbe138e3b99a605c561c49dea50e253c8306c4f4f77d9
             96f898db64c5d8a0a15c6efa28b0934bf0b6f2b01950d877230fe4401078420fd6dd3"
        ),
        rsa_keypair.public.n.as_bytes(),
    );
    assert_eq!(
        &hex!(
            "70d639ad77847429fed4f0cb037c5760127f3ae69cb03977e36675529c3f6a00941a14155c36e9bb68bcf06
             594c142c1fe22e4ab4b088886879d6cbbcf3f499669ce861354e074c38b73c2797d0b81d8504c4f3fece179
             52dc3778a9300905f7ef458e435eca801a4c93daceddc59452c37c930b578c543ad8ae384c5cd600dca8e8b
             c9dfe948f5e2a718649b2b5fc1868b4911990d862e6ff66a02363681090855911a610a79fa7bcfe83713c2b
             ae6183528d7b938b5eea86f29bfead93994fb96287cef503ea159fa0986be168fbf1402dbaa028f22082c1a
             6cf80dd66f8637cf3d18c677fd72ea97d4849387670b1b3dc87f2295e6b77aa0e36be8a37cc864f2786dfaa
             3c4522836e4433d8dd9c464c155a78ac19e49b01f56959003baacd5183c0aeef1ff0da9988feaa1db7aadfd
             5e243ea5095d3448f5411e198ff29e2bafda1c26007effe369686171615625af5634dc98ce81ed024ea559d
             8bfefc6e7172a9d1409273f94d0235b2e76bb41a532db6ce9f0a28d4eb0db70abced95462eec5e222d3b3e6
             1c330a9c97913a7de628524e3daa24ce93d2acf9440e03c57063e3c7509332addf37d5c840375a0b9de1d68
             822cdcf88b44cd6aea8bfc646ce52f9d05e7e867a32d462e35a163c15b7df00e9c0a870345b86e7882971b4
             d79d42507ade7c26e6db29f52fbe58430f915c554145faa950ae6b6e4f87bf24a61"
        ),
        rsa_keypair.private.d.as_bytes()
    );
    assert_eq!(
        &hex!(
            "580f3580a2ad54f7e4390a99a05b377730aebe631cd41f6452424e03763d2d43af327f919aa96bf748a3041
             fd6f76b471b4b8029ebac01df18692b1612c5d046640083ab123546495bbfab77d5f9a4b8ebeffce997417e
             a5625b070be1cf8c5b253edd826be6042ee1f71a13b72e8df6fdcb7f8a945ef5929a4c790803bc31feff24f
             8f148926ea3aa02c690889baeeb1e727295642f13955067fee400b230876252fd9dcf1f56a4307d3d717cf0
             235833fdc93947a2b4ed45d43df51087d91d59eb0bf09fe6f45036b23c944addce2976b805425c6841129be
             5b17c4dcc41d62daa053d06a1fbfd3c20543a63066ad69933ae64538c305ae645d81557a6f3c9"
        ),
        rsa_keypair.private.iqmp.as_bytes()
    );
    assert_eq!(
        &hex!(
            "00e2b7aa95621ec065acd1b9edd46090c715e1d212f11ac24f61c3016a4b411a25c635007654dc19a145531
             f9d49f796965a28f67575a5a9bf852b53474c4345cf604d40b614e31d50f0ca56414f152b49d6b92d8767d4
             70a5a10afb6e546189d6e99739aeff7a081d96fd5c1646c5abbb8481df936c65aad51a553596e16f49b09f8
             8175d2c938f92ecaccc61313523fd678533007f05cab51dfd16cfaf3439033bd3a4d845c08e34097b6f7ecd
             0613082e7d1830f936e29c7865c2b8acd30870dd20679788e0b2aaa2285d35ea7347c4083e2ee9c92dcb11e
             ea114245c5f22d7afeb9d51cbc0ca17116261fac8a8f3c3054da1f53ad297f8ce184663ec4e617d"
        ),
        rsa_keypair.private.p.as_bytes()
    );
    assert_eq!(
        &hex!(
            "00cba436332637becfdbdec249dbc121e5309c9964f2053c221b58b601846afd7cc8b788d6bf9b71345b1bd
             de7b367204e010ee60c2126352476ce98899b72035eb5f2ae8dd9754dd500354c418cbbf75dfd4bf2029a9a
             3c8e097efdb334e8228a738b1c3fac43b4822364a54b4c348042369b59cf086b25db23226f71edeae58e77f
             c6f10493641c4254c28999be5628cd74e259d5fe5d39c98a9c0b8543bd58c89bb34ea19e18af714f1446e29
             3d09881ed7fa5f49b374bcab97dafa067e8eb63bc9ddf2668bf3ebb2bb585d7b12ff591e6ff34889196b9e5
             293809f168d681bb7b09680fef093c8a28ef0d25568fce4ab5e879fee21a7525ac08caf9efa2d8f"
        ),
        rsa_keypair.private.q.as_bytes()
    );
    assert_eq!("user@example.com", key.comment());
}

#[cfg(all(feature = "alloc"))]
#[test]
fn decode_custom_algorithm_openssh() {
    let key = PrivateKey::from_openssh(OPENSSH_OPAQUE_EXAMPLE).unwrap();
    assert!(
        matches!(key.algorithm(), Algorithm::Other(name) if name.as_str() == "name@example.com")
    );
    assert_eq!(Cipher::None, key.cipher());
    assert_eq!(KdfAlg::None, key.kdf().algorithm());
    assert!(key.kdf().is_none());

    let opaque_keypair = key.key_data().other().unwrap();
    assert_eq!(
        &hex!("888f24ee17adfed0091e67e485fb9844cfed6072cac1d06390e4005f5015b44f"),
        opaque_keypair.public.as_ref(),
    );
    assert_eq!(
        &hex!("986c953b4b5efb3285ff207c1ca5ee39a59047bc488fbc3b1ef036efc7575c75"),
        opaque_keypair.private.as_ref(),
    );

    assert_eq!(key.comment(), "comment@example.com");
}

#[cfg(all(feature = "alloc"))]
#[test]
fn encode_dsa_openssh() {
    encoding_test(OPENSSH_DSA_EXAMPLE)
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p256_openssh() {
    encoding_test(OPENSSH_ECDSA_P256_EXAMPLE)
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p384_openssh() {
    encoding_test(OPENSSH_ECDSA_P384_EXAMPLE)
}

#[cfg(all(feature = "alloc", feature = "ecdsa"))]
#[test]
fn encode_ecdsa_p521_openssh() {
    encoding_test(OPENSSH_ECDSA_P521_EXAMPLE)
}

#[cfg(all(feature = "alloc"))]
#[test]
fn encode_ed25519_openssh() {
    encoding_test(OPENSSH_ED25519_EXAMPLE)
}

#[cfg(all(feature = "alloc"))]
#[test]
fn encode_rsa_3072_openssh() {
    encoding_test(OPENSSH_RSA_3072_EXAMPLE)
}

#[cfg(all(feature = "alloc"))]
#[test]
fn encode_rsa_4096_openssh() {
    encoding_test(OPENSSH_RSA_4096_EXAMPLE)
}

#[cfg(all(feature = "alloc"))]
#[test]
fn encode_custom_algorithm_openssh() {
    encoding_test(OPENSSH_OPAQUE_EXAMPLE)
}

/// Common behavior of all encoding tests
#[cfg(all(feature = "alloc"))]
fn encoding_test(private_key: &str) {
    let key = PrivateKey::from_openssh(private_key).unwrap();

    // Ensure key round-trips
    let pem = key.to_openssh(LineEnding::LF).unwrap();
    let key2 = PrivateKey::from_openssh(&*pem).unwrap();
    assert_eq!(key, key2);

    #[cfg(feature = "std")]
    if !matches!(key.algorithm(), Algorithm::Other(_)) {
        encoding_integration_test(key)
    }
}

/// Parse PEM encoded using `PrivateKey::to_openssh` using the `ssh-keygen` utility.
#[cfg(all(feature = "std"))]
fn encoding_integration_test(private_key: PrivateKey) {
    let fingerprint = private_key
        .fingerprint(Default::default())
        .to_string()
        .replace(':', "-")
        .replace('/', "_");

    let path = scratch_path(&fingerprint);

    private_key
        .write_openssh_file(&path, LineEnding::LF)
        .unwrap();

    let public_key = match process::Command::new("ssh-keygen")
        .args(["-y", "-f", path.to_str().unwrap()])
        .output()
    {
        Ok(output) => {
            assert_eq!(output.status.code().unwrap(), 0);
            let ssh_keygen_output = std::str::from_utf8(&output.stdout).unwrap();
            PublicKey::from_openssh(ssh_keygen_output).unwrap()
        }
        Err(err) => {
            if err.kind() == io::ErrorKind::NotFound {
                eprintln!("couldn't find 'ssh-keygen'! skipping test");
                return;
            } else {
                panic!("error invoking ssh-keygen: {}", err)
            }
        }
    };

    // Ensure ssh-keygen successfully parsed our public key
    assert_eq!(&public_key, private_key.public_key());
}
