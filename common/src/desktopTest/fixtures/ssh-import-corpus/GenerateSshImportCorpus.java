// Test-only deterministic fixture generator. This file is not part of a Gradle source set.
// Compile it with JDK 21 and BC 1.84 (bcprov, bcpkix, and bcutil) on the classpath.

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.EncryptionScheme;
import org.bouncycastle.asn1.pkcs.KeyDerivationFunc;
import org.bouncycastle.asn1.pkcs.PBES2Parameters;
import org.bouncycastle.asn1.pkcs.PBKDF2Params;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;

public final class GenerateSshImportCorpus {
    private static final char[] PASSWORD = "passphrase".toCharArray();
    private static final int ITERATIONS = 4096;

    private static final String[] LEGACY_PEM_ALGORITHMS = {
        "AES-128-CBC", "AES-128-CFB", "AES-128-OFB", "AES-128-ECB",
        "AES-192-CBC", "AES-192-CFB", "AES-192-OFB", "AES-192-ECB",
        "AES-256-CBC", "AES-256-CFB", "AES-256-OFB", "AES-256-ECB",
        "DES-CBC", "DES-CFB", "DES-OFB", "DES-ECB",
        "DES-EDE-CBC", "DES-EDE-CFB", "DES-EDE-OFB", "DES-EDE", "DES-EDE-ECB",
        "DES-EDE3-CBC", "DES-EDE3-CFB", "DES-EDE3-OFB", "DES-EDE3", "DES-EDE3-ECB",
        "BF-CBC", "BF-CFB", "BF-OFB", "BF-ECB",
        "RC2-CBC", "RC2-CFB", "RC2-OFB", "RC2-ECB",
        "RC2-40-CBC", "RC2-40-CFB", "RC2-40-OFB", "RC2-40-ECB",
        "RC2-64-CBC", "RC2-64-CFB", "RC2-64-OFB", "RC2-64-ECB",
    };

    private static final PbeAlgorithm[] PKCS8_ALGORITHMS = {
        pbes2("SHA1", "PBKDF2WithHmacSHA1", PKCSObjectIdentifiers.id_hmacWithSHA1, 128),
        pbes2("SHA1", "PBKDF2WithHmacSHA1", PKCSObjectIdentifiers.id_hmacWithSHA1, 256),
        pbes2("SHA224", "PBKDF2WithHmacSHA224", PKCSObjectIdentifiers.id_hmacWithSHA224, 128),
        pbes2("SHA224", "PBKDF2WithHmacSHA224", PKCSObjectIdentifiers.id_hmacWithSHA224, 256),
        pbes2("SHA256", "PBKDF2WithHmacSHA256", PKCSObjectIdentifiers.id_hmacWithSHA256, 128),
        pbes2("SHA256", "PBKDF2WithHmacSHA256", PKCSObjectIdentifiers.id_hmacWithSHA256, 256),
        pbes2("SHA384", "PBKDF2WithHmacSHA384", PKCSObjectIdentifiers.id_hmacWithSHA384, 128),
        pbes2("SHA384", "PBKDF2WithHmacSHA384", PKCSObjectIdentifiers.id_hmacWithSHA384, 256),
        pbes2("SHA512", "PBKDF2WithHmacSHA512", PKCSObjectIdentifiers.id_hmacWithSHA512, 128),
        pbes2("SHA512", "PBKDF2WithHmacSHA512", PKCSObjectIdentifiers.id_hmacWithSHA512, 256),
        pbes2("SHA512-224", "PBKDF2WithHmacSHA512/224", PKCSObjectIdentifiers.id_hmacWithSHA512_224, 128),
        pbes2("SHA512-224", "PBKDF2WithHmacSHA512/224", PKCSObjectIdentifiers.id_hmacWithSHA512_224, 256),
        pbes2("SHA512-256", "PBKDF2WithHmacSHA512/256", PKCSObjectIdentifiers.id_hmacWithSHA512_256, 128),
        pbes2("SHA512-256", "PBKDF2WithHmacSHA512/256", PKCSObjectIdentifiers.id_hmacWithSHA512_256, 256),
    };

    private GenerateSshImportCorpus() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 5) {
            throw new IllegalArgumentException(
                "usage: GenerateSshImportCorpus <unencrypted-rsa-pem> <output-dir> " +
                    "[<v2-rsa-none-ppk> <v2-ed25519-none-ppk> <v2-ed25519-encrypted-ppk>]"
            );
        }
        Security.addProvider(new BouncyCastleProvider());
        Path output = Path.of(args[1]);
        Files.createDirectories(output.resolve("legacy-pem"));
        Files.createDirectories(output.resolve("pkcs8-pbe"));

        PrivateKey privateKey = readPrivateKey(Path.of(args[0]));
        for (String algorithm : LEGACY_PEM_ALGORITHMS) {
            String fileName = algorithm.toLowerCase().replace('_', '-').replace("/", "-") + ".pem";
            Files.writeString(
                output.resolve("legacy-pem").resolve(fileName),
                encryptLegacyPem(privateKey, algorithm),
                StandardCharsets.US_ASCII
            );
        }
        for (PbeAlgorithm algorithm : PKCS8_ALGORITHMS) {
            Files.writeString(
                output.resolve("pkcs8-pbe").resolve(algorithm.fileName() + ".pem"),
                encryptPkcs8(privateKey.getEncoded(), algorithm),
                StandardCharsets.US_ASCII
            );
        }
        if (args.length == 5) {
            Path ppkOutput = output.resolve("ppk");
            Files.createDirectories(ppkOutput);
            Files.writeString(
                ppkOutput.resolve("v3-rsa-none.ppk"),
                rewriteUnencryptedPpkV3(Files.readString(Path.of(args[2]))),
                StandardCharsets.US_ASCII
            );
            Files.writeString(
                ppkOutput.resolve("v3-ed25519-none.ppk"),
                rewriteUnencryptedPpkV3(Files.readString(Path.of(args[3]))),
                StandardCharsets.US_ASCII
            );
            Files.writeString(
                ppkOutput.resolve("v1-ed25519-aes256-cbc.ppk"),
                Files.readString(Path.of(args[4])).replaceFirst(
                    "PuTTY-User-Key-File-2:",
                    "PuTTY-User-Key-File-1:"
                ),
                StandardCharsets.US_ASCII
            );
        }
    }

    private static String rewriteUnencryptedPpkV3(String source) throws Exception {
        List<String> lines = source.lines().toList();
        String keyType = lines.get(0).substring(lines.get(0).indexOf(':') + 2);
        String encryption = lines.get(1).substring("Encryption: ".length());
        if (!"none".equals(encryption)) {
            throw new IllegalArgumentException("PPK v3 compatibility derivative must be unencrypted");
        }
        String comment = lines.get(2).substring("Comment: ".length());
        int publicLineCount = Integer.parseInt(lines.get(3).substring("Public-Lines: ".length()));
        int publicStart = 4;
        byte[] publicBlob = Base64.getDecoder().decode(
            String.join("", lines.subList(publicStart, publicStart + publicLineCount))
        );
        int privateHeader = publicStart + publicLineCount;
        int privateLineCount = Integer.parseInt(
            lines.get(privateHeader).substring("Private-Lines: ".length())
        );
        int privateStart = privateHeader + 1;
        byte[] privateBlob = Base64.getDecoder().decode(
            String.join("", lines.subList(privateStart, privateStart + privateLineCount))
        );

        ByteArrayOutputStream macInput = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(macInput)) {
            writePpkString(data, keyType.getBytes(StandardCharsets.UTF_8));
            writePpkString(data, encryption.getBytes(StandardCharsets.UTF_8));
            writePpkString(data, comment.getBytes(StandardCharsets.UTF_8));
            writePpkString(data, publicBlob);
            writePpkString(data, privateBlob);
        }
        byte[] macKey = MessageDigest.getInstance("SHA-256")
            .digest("putty-private-key-file-mac-key".getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        String macHex = java.util.HexFormat.of().formatHex(mac.doFinal(macInput.toByteArray()));

        StringBuilder output = new StringBuilder();
        output.append("PuTTY-User-Key-File-3: ").append(keyType).append('\n');
        for (int i = 1; i < privateStart + privateLineCount; i++) {
            output.append(lines.get(i)).append('\n');
        }
        output.append("Private-MAC: ").append(macHex).append('\n');
        return output.toString();
    }

    private static void writePpkString(DataOutputStream output, byte[] value) throws Exception {
        output.writeInt(value.length);
        output.write(value);
    }

    private static PrivateKey readPrivateKey(Path path) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(path)))) {
            Object object = parser.readObject();
            PrivateKeyInfo info = object instanceof PEMKeyPair pair ? pair.getPrivateKeyInfo() : (PrivateKeyInfo) object;
            return new JcaPEMKeyConverter().setProvider("BC").getPrivateKey(info);
        }
    }

    private static String encryptLegacyPem(PrivateKey key, String algorithm) throws Exception {
        StringWriter buffer = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(buffer)) {
            writer.writeObject(
                key,
                new JcePEMEncryptorBuilder(algorithm)
                    .setProvider("BC")
                    .setSecureRandom(new DeterministicSecureRandom("legacy-pem:" + algorithm))
                    .build(PASSWORD)
            );
        }
        return buffer.toString();
    }

    private static String encryptPkcs8(byte[] plaintext, PbeAlgorithm algorithm) throws Exception {
        byte[] salt = deterministicBytes("pkcs8-salt:" + algorithm.jcaName(), 16);
        byte[] iv = deterministicBytes("pkcs8-iv:" + algorithm.jcaName(), 16);
        PBEKeySpec keySpec = new PBEKeySpec(PASSWORD, salt, ITERATIONS, algorithm.aesBits());
        SecretKey derivedKey = SecretKeyFactory.getInstance(algorithm.pbkdf2Name()).generateSecret(keySpec);
        SecretKey key = new SecretKeySpec(derivedKey.getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            new IvParameterSpec(iv),
            new DeterministicSecureRandom("pkcs8-cipher:" + algorithm.jcaName())
        );
        byte[] ciphertext = cipher.doFinal(plaintext);
        AlgorithmIdentifier prf = new AlgorithmIdentifier(algorithm.prfOid(), DERNull.INSTANCE);
        PBKDF2Params pbkdf2 = new PBKDF2Params(salt, ITERATIONS, algorithm.aesBits() / 8, prf);
        KeyDerivationFunc derivation = new KeyDerivationFunc(PKCSObjectIdentifiers.id_PBKDF2, pbkdf2);
        EncryptionScheme encryption = new EncryptionScheme(algorithm.aesOid(), new DEROctetString(iv));
        PBES2Parameters pbes2 = new PBES2Parameters(derivation, encryption);
        AlgorithmIdentifier encryptionAlgorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, pbes2);
        byte[] encoded = new org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo(
            encryptionAlgorithm,
            ciphertext
        ).getEncoded();
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN ENCRYPTED PRIVATE KEY-----\n" + body + "\n-----END ENCRYPTED PRIVATE KEY-----\n";
    }

    private static byte[] deterministicBytes(String label, int length) throws Exception {
        byte[] output = new byte[length];
        byte[] seed = label.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        int counter = 0;
        while (offset < output.length) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            digest.update(new byte[] {
                (byte) (counter >>> 24),
                (byte) (counter >>> 16),
                (byte) (counter >>> 8),
                (byte) counter,
            });
            byte[] block = digest.digest();
            int count = Math.min(block.length, output.length - offset);
            System.arraycopy(block, 0, output, offset, count);
            offset += count;
            counter++;
        }
        return output;
    }

    private static PbeAlgorithm pbes2(
        String prfLabel,
        String pbkdf2Name,
        ASN1ObjectIdentifier prfOid,
        int aesBits
    ) {
        String jcaName = "PBEWithHmac" + prfLabel.replace("-", "/") + "AndAES_" + aesBits;
        String fileName = "pbes2-hmac-" + prfLabel.toLowerCase() + "-aes" + aesBits;
        ASN1ObjectIdentifier aesOid = aesBits == 128
            ? NISTObjectIdentifiers.id_aes128_CBC
            : NISTObjectIdentifiers.id_aes256_CBC;
        return new PbeAlgorithm(jcaName, fileName, pbkdf2Name, prfOid, aesBits, aesOid);
    }

    private record PbeAlgorithm(
        String jcaName,
        String fileName,
        String pbkdf2Name,
        ASN1ObjectIdentifier prfOid,
        int aesBits,
        ASN1ObjectIdentifier aesOid
    ) {
    }

    private static final class DeterministicSecureRandom extends SecureRandom {
        private final String label;
        private int request;

        private DeterministicSecureRandom(String label) {
            this.label = label;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            try {
                byte[] generated = deterministicBytes(label + ":" + request++, bytes.length);
                System.arraycopy(generated, 0, bytes, 0, bytes.length);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
