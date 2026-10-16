---
title: Watchtower
description: "The security checks Keyguard runs on your vault, how each one works, and how they protect your privacy."
section: security
type: feature
order: 20
platforms: all
related: [items, url-matching, gpg-keys, generator]
---

Watchtower checks the items in your vault for security risks, such as leaked,
reused or weak passwords, and for maintenance problems, such as duplicates.
Each check is a card in **Watchtower** that counts the affected items.

## Fix an alert

1. Open **Watchtower**.
2. Select a check, such as **Reused Passwords**. The affected items appear.
3. Select an item and fix the problem. For example, change the password with the
   [Generator](/docs/generator/), or select **Auto-fix insecure URL** in the
   menu of an `http://` URL.

To see only alerts you haven't reviewed, select **Review new alerts** at the top
of **Watchtower**. Opening an item marks its alerts as read, and
**Mark all as read** marks the rest.

## Ignore an alert for an item

Some alerts don't apply to an item, such as a shared throwaway account that
reuses a password.

1. Open the item.
2. In the item's menu, select **Configure Watchtower alerts**.
3. Clear the alerts to ignore.
4. Select **OK**.

The item no longer counts toward those checks, and the **Ignored alerts** filter
in the vault lists it. Weak passwords can't be ignored.

## Watchtower checks

### Security

The **Password strength** bar at the top of **Watchtower** sorts your passwords
from **Very strong** to **Weak**, by the estimated time to crack each one. Weak
passwords also raise an alert.

| Check | What it flags |
| --- | --- |
| **Pwned Passwords** | Passwords found in known data breaches, even once. |
| **Vulnerable Accounts** | Logins whose website had a breach that exposed passwords or other sensitive data after you last changed the password. |
| **Reused Passwords** | Passwords shared by two or more items. |
| **Weak SSH keys** | RSA keys shorter than 2048 bits. |
| **Weak GPG keys** | Primary keys or subkeys that use RSA shorter than 2048 bits, DSA or ElGamal. |
| **Inactive two-factor authentication** | Logins for sites that support an authenticator app, when the item has no one-time password. Logins that use only a passkey are skipped. |
| **Insecure Websites** | URLs that use `http://`, which sends everything unencrypted. `localhost` and private network addresses, such as `192.168.1.1`, are skipped. |
| **Available Passkeys** | Logins for sites that support passkey sign-in, when the item has no passkey. |

### Maintenance

| Check | What it flags |
| --- | --- |
| **Duplicate Items** | Items of the same type that look like copies. **Tolerance** on the check's screen sets how alike they must be, from **Min** to **Max**. Merge a group with **Merge into…**, as in [Merge duplicates](/docs/bulk-actions/#merge-duplicates). |
| **Incomplete Items** | Items with a blank or placeholder name, such as `login` or `todo`, and items missing key data: a login without a username, an identity without a name, or a card without a number. Items with custom fields or attachments, passkey-only logins and API-token-style items are skipped. |
| **Expiring Items** | Payment cards that have expired or expire within three months. |
| **Unusable GPG keys** | [GPG keys](/docs/gpg-keys/) that are revoked, expired or malformed, whose fingerprint doesn't match the key, that have no usable signing or decryption key, or whose GPG agent data is missing or inconsistent. |
| **GPG key publishing** | Keys whose keyserver status is unknown, not found, unverified, revoked or stale. Stale means not checked in 30 days. Upload, verify or refresh the key from the item. |
| **Duplicate URIs** | Items with two URIs that cover the same website under [URL matching](/docs/url-matching/), such as `example.com` and `www.example.com/login` with **Base domain** matching. Remove the extra URI. |
| **Broad URI match detection** | URIs set to **Base domain** while another URI in the vault matches the same domain by **Host**. Switch the URI to a narrower **Match detection**. |
| **Trashed Items** | Items in the trash. Many trashed items slow down the vault. |
| **Empty Folders** | Folders with no items. |

Watchtower skips archived and trashed items in every check except
**Trashed Items**.

## Settings

These settings are in **Settings > Watchtower**. Turning a check off hides its
card.

| Setting | What it does |
| --- | --- |
| **Check for pwned passwords** | Runs **Pwned Passwords**. |
| **Check for data breaches** | Runs **Vulnerable Accounts**. |
| **Have I Been Pwned API token** | Your own Have I Been Pwned API key, for the item actions **Check the username in known data breaches** and **Check the email in known data breaches**. Without a key, these go through the breach check of your Bitwarden server, so KeePass-only vaults need one. |
| **Check for inactive two-factor authentication** | Runs **Inactive two-factor authentication**. |
| **Check for inactive passkeys** | Runs **Available Passkeys**. |

## How the checks respect your privacy

- **Pwned Passwords** uses the Have I Been Pwned range API with k-anonymity.
  Only the first five characters of the password's SHA-1 hash leave your
  device, and the comparison happens on the device. Your passwords, even
  hashed, are never sent.
- **Vulnerable Accounts** downloads the public
  [Have I Been Pwned](https://haveibeenpwned.com/) breach catalog and compares
  it with your items on the device.
- **Inactive two-factor authentication** and **Available Passkeys** use
  directories bundled with the app, from [2factorauth](https://2fa.directory/)
  and the [passkeys directory](https://passkeys.directory/). These checks make
  no network requests.
