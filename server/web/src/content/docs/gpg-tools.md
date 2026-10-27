---
title: GPG tools
description: "Encrypt, decrypt, sign and verify text or files with the GPG keys in your vault, without GnuPG."
section: developer
type: feature
order: 60
platforms: all
related: [gpg-keys, gpg-agent, generator]
---

**GPG tools** encrypts, decrypts, signs and verifies text or files with the GPG
keys in your vault. It works inside Keyguard, so you don't need GnuPG. It
appears in the navigation once your vault has a **GPG Key** item.

### Before you begin

- A **GPG Key** item in your vault. To sign or decrypt, it needs a private key.
  The [Generator](/docs/generator/) creates one, and [Import](/docs/gpg-keys/#import)
  covers adding an existing key.

## Encrypt a message

1. Open **GPG tools** and select **Encrypt**.
2. Select **Text** and enter the message, or select **File** and choose the
   input file.
3. Under **Recipients**, select **Add recipients…** to choose keys from your
   vault. To encrypt for someone else, select **Add a public key** and paste
   their armored public key.
4. Optional: under **Sign**, choose one of your keys to sign the message too.
   The default is **Don't sign**.
5. Select **Run**. The result shows the encrypted message, ready to copy or
   save.

Every recipient needs a key that can encrypt.

## Decrypt a message

1. Open **GPG tools** and select **Decrypt**.
2. Paste the message into **Encrypted message**, or select **File** and choose
   the input file.
3. Select **Run**. Keyguard tries the private keys in your vault and shows the
   decrypted result.

## Sign a message

1. Open **GPG tools** and select **Sign**.
2. Select **Text** and enter the message, or select **File** and choose the
   input file.
3. Under **Signing key**, choose one of your keys.
4. For text, choose **Cleartext** to wrap the signature around the text, or
   **Detached** to get a separate signature.
5. Select **Run**. The result shows the signature, ready to copy or save.

A file always gets a detached signature in a separate file.

## Verify a signature

1. Open **GPG tools** and select **Verify**.
2. For text, choose **Inline** and paste the signed text, or choose
   **Detached** and paste the text and its signature. For a file, choose the
   **Input file** and the **Signature file**.
3. If the signer's public key isn't in your vault, select **Add a public key**
   and paste it.
4. Select **Run**. The result says whether the signature is valid, and who
   signed it when.

## Results

| Result | Meaning |
| --- | --- |
| **Valid signature** | The signature matches the message and the signer's key. |
| **Invalid signature** | The message or the signature changed after signing. |
| **Missing public key** | No key in your vault or pasted for this operation matches the signature. |

A result also warns when the signing key is revoked or expired, the signature
has expired, or the signature uses a weak digest algorithm. Decryption warns
when the message was decrypted with a weak RSA key or a legacy ElGamal key.

Signatures use SHA-256 over canonical text.

## Files

| Operation | Output file |
| --- | --- |
| Encrypt | `<NAME>.gpg.asc`, or `<NAME>.gpg` with **Armor** off |
| Sign | `<NAME>.sig.asc`, or `<NAME>.sig` with **Armor** off |
| Decrypt | The input's name without its `.gpg`, `.pgp` or `.asc` ending, or with `.decrypted` added |

**Armor**, on by default, saves an encrypted file or a signature as ASCII text
instead of binary data.
