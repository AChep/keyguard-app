---
title: License keys
description: Use your premium purchase on another device, including while offline.
category: reference
order: 11
---

A license key is a portable proof of a Keyguard premium entitlement. It lets you
use an eligible premium purchase on another device or app build. The license key is
intentionally designed in a way to allow local key verification — please, do not abuse! 😀

If you bought premium through Google Play, that Android install can normally
detect the purchase automatically. A license key is useful when another device
cannot see that store purchase directly. In the app, this may be shown as an
**entitlement token**.

Treat the key as private. It is not tied to a specific device or Bitwarden
account, so anyone who has the key may be able to use the same entitlement.

## Create a license key

On the device where your premium purchase is active:

1. Open **Settings → Keyguard Premium**.
2. Choose **Entitlement token**.
3. Sync the purchase.
4. Copy the generated license key.

If Keyguard says there is no eligible purchase, check that you are using the
same store account that bought premium.

Creating a license key from a store purchase requires an internet connection,
because Keyguard has to verify the purchase before creating a portable key.

The generated key does not contain your vault data, store password, or payment
details. It contains license information that Keyguard can verify locally.

## Use a license key on another device

On the other device:

1. Open **Settings → Keyguard Premium**.
2. Choose **Link entitlement token**.
3. Paste the license key.
4. Save.

If the key is valid and still active, premium features unlock on that device.

## What the key contains

A license key contains a signed license payload. Keyguard checks the signature
with a public verification key built into the app.

The payload includes:

- a license id;
- the premium tier;
- whether the license is a subscription or lifetime purchase;
- for subscriptions, the paid-through month.

Because the payload is signed, changing the text of the key invalidates it.
Because the app can verify the signature locally, it can decide whether the key
is structurally valid even before contacting the license server.

## How offline use works

A license key is designed to keep working without an internet connection.

This means:

- a valid lifetime key can unlock premium while offline;
- a valid subscription key can unlock premium while offline until its
  paid-through month ends;
- Keyguard does not need to contact the license server every time you open the
  app.

If the key cannot be verified locally, Keyguard treats it as invalid. If the key
can be verified locally but the device is offline, Keyguard uses the signed
payload and the last known license status saved in the vault settings.

## Online status refresh

When the app is online, Keyguard may refresh the license status with the license
server. The server response can confirm that the license is still active or
report a later status such as refunded, revoked, expired, pending, or invalid.

The server also tells Keyguard when it should check again. Keyguard does not
need to call the server on every launch. If a refresh fails because you are
offline or the server is temporarily unreachable, Keyguard keeps using the last
valid local license state instead of locking you out immediately.

For subscriptions, a successful refresh may return an updated key when a renewal
extends the paid-through month. Keyguard stores the updated key automatically.

## Remove a license key

Open **Link entitlement token**, clear the field, and save. This removes the
license key from that vault on the current device. It does not cancel your store
purchase.

## License status

Keyguard may show one of these states:

- **Active**: premium is currently unlocked.
- **Grace**: premium is still available while the store resolves a billing
  issue.
- **Expired**: the paid period ended.
- **Revoked** or **Refunded**: the purchase no longer grants premium.
- **Pending**: the purchase is not fully active yet.
- **Invalid**: the key could not be verified.

## Privacy

When syncing a purchase, Keyguard checks the purchase with the store and creates
a portable license key. The license server is only used for purchase validation
and license status checks. Your vault items, passwords, passkeys, notes, and
attachments are not sent to the license server.

## Troubleshooting

If a key does not work:

- Copy the whole key again and make sure no characters are missing.
- Confirm the original purchase is still active.
- Try syncing the entitlement token again on the device with the purchase.
- If the purchase was refunded, canceled, or expired, the key will stop
  unlocking premium.
