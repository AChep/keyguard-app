---
title: License keys
description: "Activate Keyguard Premium with a lifetime license bought on the web or a key from an existing purchase."
section: get-started
type: feature
order: 30
platforms: all
related: [premium]
---

A license key activates Premium after a web purchase. It also lets you use a
Google Play purchase on a device signed in to another Google account.
The app calls it an **entitlement token**.

:::caution
Anyone with your license key can use your premium on any device. Keep it
private.
:::

## Offline use

Keyguard verifies a license key on the device, so the key works offline.

- A lifetime key unlocks premium with no end date.
- A subscription key unlocks premium until its paid-through month ends.

When online, Keyguard checks the license status with the license server. If the
check fails, Keyguard keeps the last known status. After a subscription renews,
the check can return an updated key, and Keyguard stores it.

## Privacy

A license key holds a license ID, the tier, the purchase type and, for a
subscription, the paid-through month. The license server only validates
purchases and checks license status. Your vault items, passwords, passkeys,
notes and attachments never reach it.

## Buy a lifetime license on the web

Use these steps when the Purchase page offers web checkout.

1. Open the [Purchase](/purchase/) page.
2. Select **Buy lifetime license**.
3. Complete payment in Paddle.
4. Keep the tab open until your key appears.
5. Select **Copy key** or **Download key**.

The key is not sent by email. Save it in a secure place before closing the tab.
Refreshing the same tab restores the checkout while its session is valid.
If payment is still being confirmed, select **Check payment status**.

## Create a key from Google Play

On the device with the purchase, while online:

1. Open **Settings > Keyguard Premium**.
2. Select **Obtain entitlement token**. Keyguard verifies the purchase with
   Google Play and shows the key under the option.
3. Copy the key.

## Use a license key on another device

1. Open **Settings > Keyguard Premium**.
2. Select **Link existing entitlement token**.
3. In **Entitlement token**, paste the key.
4. Select **OK**. If the key is valid and active, premium unlocks.

## Remove a license key

1. Open **Settings > Keyguard Premium**.
2. Select **Link existing entitlement token**.
3. Clear **Entitlement token**.
4. Select **OK**. Keyguard removes the key from the vault.

Removing a key doesn't cancel a subscription or refund a purchase.
Manage a subscription in the store where you bought it.

## License status

Keyguard shows the status of a key next to it in
**Settings > Keyguard Premium**.

| Status | Meaning |
| --- | --- |
| Active | Premium is unlocked. |
| Grace period | Premium stays unlocked while the store resolves a billing issue. |
| Expired | The paid period ended. |
| Revoked | The purchase no longer grants premium. |
| Refunded | The purchase was refunded and no longer grants premium. |
| Pending | The purchase isn't fully active yet. |
| Invalid | Keyguard couldn't verify the key. |

## Troubleshooting

### "No eligible purchase found"

The Google account on this device didn't buy premium. In Google Play, switch to
the account that bought premium, then select **Obtain entitlement token** again.

### "Could not obtain entitlement token"

Keyguard couldn't verify the purchase. Check the internet connection, then try
again.

### "Invalid or expired key"

The key is incomplete or changed, or its purchase was refunded, revoked or
expired. Copy the whole key again. For a Google Play purchase, select
**Obtain entitlement token** on the device with the purchase.

### Lost web purchase key

If you lost the key or closed the checkout tab before saving it, contact
[keyguard@artemchep.com](mailto:keyguard@artemchep.com) with your Paddle receipt.
Support can help recover your purchase.
