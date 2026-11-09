---
title: Linked devices
description: Link Keyguard on your devices to sync email forwarders, wordlists and URL overrides between them directly, without a server of your own.
section: accounts
type: feature
order: 70
platforms: [android, linux, macos, windows]
premium: true
stage: beta
related: [email-relays, generator, premium, security, url-overrides]
---

Linked devices syncs data between the Keyguard apps on your devices. It syncs
your [email forwarders](/docs/email-relays/),
[Custom wordlists](/docs/generator/#custom-wordlists) and
[URL overrides](/docs/url-overrides/). Your devices connect to each other
directly, or through a relay when they can't.

### Before you begin

- An up-to-date Keyguard on every device you want to link.
- A [Premium](/docs/premium/) purchase or a self-hosted relay server.
- Network access to Keyguard's relay, or to your own. See
  [Use your own relay](#use-your-own-relay).

## How syncing works

Devices sync while Keyguard is open and unlocked on both at once.

**Relay storage** lets devices sync without being online together. It's on by
default and works only while every linked device has it on. Turn it off in
**Settings > Linked devices > Relay server**.

Vault items don't sync through linked devices. They sync through their own
accounts.

## Choose what a device syncs

In **Settings > Linked devices**, under **Sync on this device**, turn on the
categories this device should sync. Set them before or after you link the
device.

| Category | Data |
| --- | --- |
| Email forwarders | Services and their API keys |
| Wordlists | Lists and their words |
| URL overrides | All but the built-in **HTTPS-ify** |

A new wordlist shows up on the other devices once its words arrive.

What is off stays on this device. When you turn it on again, the newest
change wins.

## Link a device

One device shows a code and the other scans it. Let the device with a camera
scan.

Before pairing, check **Device name** and **Relay server** under **This device**.
Keyguard suggests your device's name or model when available.

1. On the first device, in **Settings > Linked devices**, select
   **Link a device > Show a code**, then enter your app password. A pairing
   code appears.
2. On the other device, in **Settings > Linked devices**, select
   **Link a device > Scan a code**.
3. Enter your app password, then scan the code. Without a camera, paste the
   text into **Pairing code**.
4. The scanning device shows 6 digits. Type them into **Digits** on the other
   device, which then shows **The digits match**.
5. On the scanning device, select **Yes, they match**. The devices are linked.

Either device can show the code. On a linked device, select **Link another
device** instead. The new device joins the link of the linked one. If both
devices are linked to other devices, unlink one of them first.

A code works once, for 3 minutes. **New code** makes another. Anyone who scans
the code can ask to join, so show it only to your own devices.

Adding, removing or unlinking a device needs your app password. Biometrics
are not enough, so a stolen device, even unlocked, can't change your devices.

Matching digits prove that no one sat between the two devices. If they differ,
select **No** or **Cancel**.

## Manage devices

The **Other devices** list shows each device's name, fingerprint, date added,
and last connection. Select a device for its actions. **This device** shows
this device's name and fingerprint.

| Action | Result |
| --- | --- |
| **Rename** | Under **This device**, select **Device name**. Your other devices show the new name after they sync. |
| **Remove** | On other devices. Confirm, then enter your app password. The device stops syncing with your linked devices, and so do the devices it added that you select. If it connects to one of them within 7 days, it learns that it was removed. It can't find your other devices on the relay. To link it again, add it from another device. |
| **Unlink this device** | Enter your app password. This device stops syncing and tries to notify connected devices. Its data, name and relay server stay. |

Offline devices learn from a notified device. Otherwise, remove the unlinked
device from their lists.

:::caution
A removed device keeps the data it already has. Keyguard can't wipe it
remotely. If it knows your app password, it can still remove your other
devices through one that missed its removal. Keyguard warns when only
devices that are no longer linked removed a device. If you didn't remove
it, link your devices again.
:::

## Use your own relay

To host the relay and a TURN server in Docker, follow the
[relay's self-hosting guide](https://github.com/AChep/keyguard-app/blob/master/server/relay/README.md).
Then, on every linked device:

1. In **Settings > Linked devices**, under **This device**, select
   **Relay server**.
2. Enter the **Relay URL**, such as `wss://relay.example.com`.
3. For a private relay, enter the **Access token**.
4. Select **Save**.

An empty **ICE servers URL** means the relay's own endpoint. An ICE
endpoint on another host doesn't get the access token. An empty **Relay URL**
means Keyguard's relay. Turn off **Allow local network** to use only TURN and
the relay.

A pairing code carries the relay URLs, but not the access token, which Keyguard
sends only to the relay it was saved for. On the device you add, set the
same relay URL and the token in **Relay server** before you scan a code.

Compose keeps relay storage in a `/data` volume. Set `RELAY_MAILBOX_DIR` empty
to turn it off.

## Connections

The devices list shows which connection each device last used.

| Connection | When Keyguard uses it |
| --- | --- |
| Local network | Both devices are on the same network, and **Allow local network** is on. |
| Direct connection | The devices are on different networks, and a STUN server helps them connect directly. |
| TURN server | No direct connection works. A TURN server forwards the encrypted traffic. |
| Relay | Nothing else connects quickly. The relay forwards the encrypted traffic. |

## Data the relay sees

The relay passes connection details between your devices.

Devices encrypt all traffic end to end with the Noise protocol, on top of the
encryption WebRTC adds, and seal each stored item for your linked devices. The
relay and the TURN server can't read or change them.

| They see | They never see |
| --- | --- |
| Your devices' IP addresses | Synced data and API keys |
| When devices connect or store items, and the sizes of encrypted messages and items, padded to size steps | Device names |
| A room ID for each pair of devices, and a random ID per connection | A room's group |

A device's rooms share one connection: the relay can link them to its IP
address.

The [Privacy policy](/privacy/) says how long Keyguard's relay keeps this data.

## Network permissions

| Platform | Prompt |
| --- | --- |
| Android | The **Local network** permission. See [Android permissions](/docs/security/#android-permissions). |
| macOS | Local network access. With the firewall on, macOS also asks to allow incoming connections. |
| Windows | Windows Firewall asks to allow Keyguard. Allow it on private networks. |

## Limitations

| Area | Limitation |
| --- | --- |
| Sync | Without relay storage, both devices must have Keyguard open and unlocked. |
| Relay storage | Best effort. The relay deletes items after 7 days, or earlier when its disk runs low. |
| Network | Devices on the same network still need the relay to find each other. |
| Wordlists | A list larger than 16 MiB, or of more than 1,048,576 words, stays on its device. A word that is in a list twice syncs once. |

## Troubleshooting

### "The code expired"

The code wasn't scanned within 3 minutes, or it was used before. Start the
pairing again, and scan the new code within 3 minutes.

### "The digits didn't match. Nothing was shared"

The code came from another device, or something on the network sat between
the devices. Pair again with a code from your own device. If the digits
differ again, use another network or your own relay.

### "Update Keyguard on this device" or "on the other device"

The devices use different pairing protocols. Update the device it names, then
pair again.

### A device shows "Never connected"

The two devices haven't connected since the device was added. Open Keyguard
and unlock it on both devices at the same time. If the screen shows "Not
connected to the relay", check the network and **Relay server** on that
device.
