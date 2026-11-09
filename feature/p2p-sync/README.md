# feature/p2p-sync

The device-to-device sync protocol of linked Keyguard apps, as pure Kotlin.

The module is sans-IO: every component takes inputs and returns values or a list of
effects. It opens no sockets, reads no clock, touches no storage and does no real
cryptography. The adapters in `common` inject those:

- `crypto.P2pCrypto`: SHA-256, HKDF, Ed25519 and randomness;
- `transport.*`: byte streams, transports, signaling, compression, and the factory of a
  session's transports;
- `clock.HlcClock`: the physical time source and the persistence of issued stamps.

## Layers

| Package | Contents |
|---|---|
| `clock` | Hybrid logical clock stamps and version vectors. |
| `model` | Sync entries, envelopes, rosters, pairing codes and canonical JSON. |
| `parts` | How an entry body is merged: last writer wins. |
| `merge` | The entry merge, signing versions, local edit publishing, envelope merging and input sanitizing. |
| `roster` | The device roster CRDT, its signatures and which devices are active. |
| `session` | Frames, the sync session state machine, multiplexing and the blob exchange. |
| `mailbox` | Relay storage: the items a device leaves on the relay, their codec and when to post them. |
| `enroll` | Adding a device: the enrollment state machine and the short authentication string. |
| `transport` | Interfaces the adapters implement. |

## Signed format

Every signed object is kept as the JSON object it was received as: a version, a delete, a
device record and a revocation. Storage, merging and relaying keep every field, also the
ones this build does not know, so a field a newer build adds survives older builds and
still verifies. The fields this build reads are a view over the object.

A signature covers its domain, a line break and the canonical JSON of
`{"context": …, "object": …}`: the object as sent without its own signature fields, and
what the object does not carry itself, such as its group. Ties and equality use the
canonical text of the object as sent. A signature is valid only in canonical base64url:
unpadded, with no stray bits.

Canonical JSON holds no numbers but integers within ±(2^53 − 1), in their one textual
form: no fraction, no exponent, no leading zeros, no `-0`; and no string, key or value, with
an unpaired surrogate, which UTF-8 can not carry. An envelope or a record with any other
number, or such a string, is malformed.

## Entries

An entry is a record of one kind: its kind and id name it, and entries of two kinds with
one id are two entries. It holds the signed versions and deletes of its devices that
nothing replaced yet. A version is the whole record as one device saved it, with its
history: the latest stamp of each writer it builds on. A delete holds only its history. A
version or delete replaces every one whose history its own covers. So:

- an entry usually holds one version. Concurrent edits leave a version each, and each part
  of the body takes the value of the version that changed it last;
- a delete that saw every version leaves nothing of the record but itself;
- an edit the delete never saw stays whole, and brings the item back on every device.

Merging is the union, without what is replaced: devices that merge the same versions, in
any order, end up with the same entry. An entry never holds more than 64 versions and
deletes: a merge that would is refused, and an envelope that holds one entry twice loses
every copy of it. A history holds at most 256 writers, as many as a roster holds devices.
The body of a version is at most 256 KiB of canonical JSON.

All entries fit one envelope. A merge or a local edit that would take them past 2 MiB less
64 KiB of canonical JSON, or past 255 Ki nodes, is refused, and the entry keeps its state; a
change that takes no more than what it replaces, such as a delete, always fits. So no device
can grow the entries until another device can no longer send them. Near the limit, devices
may refuse different entries.

A device signs each version and delete with its device key, with the group, the entry id
and its kind as context. So a device writes only as itself, whoever relays the write. No
stamp in a history is newer than the stamp of its version, and a stamp is never more than
5 minutes in the future: no device can push the history of an entry ahead of the others.

No check of an entry reads the schema of its kind. An entry of a kind this build does not
know is checked, stored and relayed like the others, and counts toward the same limits;
the app never writes it to its tables. The app checks the schema of a known kind when it
writes a record, and skips a body it can not use.

A device runs the same checks on its own versions and deletes before it publishes one, and
keeps an edit its peers would drop to itself.

A group-internal kind belongs to the group itself: no local record holds its entries, and a
device syncs them whatever else its user turned off. `OBJECT_KEY` is one entry with a fixed
id, `{"key", "covers"}`: 32 random bytes, and the devices that were inactive when its writer
wrote it, strictly sorted. Any other body counts as no key, and so does a key written by a
device that is no longer active. A device writes a new key once every active device supports
the kind, and there is no key, or it knows of an inactive device the key does not cover. A
device that knows less never writes one.

No check of an entry depends on revocations, which devices learn at different times. A
device that took a write before it learned that its writer was revoked keeps it, so every
device keeps it; a revoked device is cut off by the sessions.

## Roster

Each device has two signing keys, and two encryption keys: its X25519 Noise key, `noisePub`,
and its ML-KEM-1024 key, `keys.mlkem1024`, which relay storage seals to. Its device key signs
its own record. Its authority key admits and removes devices, itself included; the app keeps it
encrypted under the master password alone, so a stolen device, even unlocked, can not change
who is in the group.

The identity of a device is its record without its mutable fields, `label`, `updatedAt`,
`caps` and `keys`, and without its signatures; a field a newer build adds is part of it.
`keys` holds the public encryption keys by algorithm, so a device may add one of a new
algorithm, or replace one, without a new identity; a key this build does not know is kept
and ignored. The
admission covers the identity; the self-signature covers the whole record but its
signatures. Every roster signature has the group id as context.

The group id derives from the founder: the first 16 bytes of the SHA-256 of
`keyguard-group/v1`, a line break and the canonical identity of the founder record, as a
UUID. A roster whose founder does not derive the group id holds nothing, and a device that
joins checks it.

A received roster is checked against the stored one: every admission must lead back to the
stored founder, and a known device keeps its identity. So a peer can neither replace the
founder nor swap the admission of a device for one it signed. A label is 1 to 64 Unicode
code points, without control characters, line or paragraph separators, or bidirectional
controls; a platform is at most 32 printable ASCII characters; a record is at most 4 KiB of
canonical JSON, nested at most 8 deep. A record that breaks a rule is dropped. A revocation is
at most 16 KiB, nested at most 8 deep, with a seal of at most 256 identities. A device without
an authority key signs its leave with its device key, so of its leaves only the first counts.
A roster holds at most 256 device records, and fits a ROSTER message: a merge that would hold
more, or take more than 2 MiB less 64 KiB, is refused whole. So no device can grow the roster
until its revocation no longer travels, or until no enrollment bundle can carry it. A device
that can't take a peer's roster takes none of the entries that came with it.

Each record lists the `caps` of its device: the version of the rules it follows, the
entry kinds it supports, and the `features` its user allows, such as `relay-storage`. The
group follows the lowest rules version of its active devices, and a device publishes no
entry of a kind that an active device does not support. There is one rules version, 1. A
change to how the versions of a kind compose ships as a new kind: a build keeps and relays
the entries of a kind it does not know without reading them, and no device writes the new
kind until every active device supports it. A device whose record lists other caps than
its build and settings have signs the record again, as a rename does.

Roster records are signed, but their stamps are not proof of anything: a revoked device
keeps its keys and can stamp a record with any time. So the activity of a device never
depends on the order of stamps. A revocation seals the devices the revoked device
admitted, as far as its signer knows them, by the SHA-256 of their whole identity, strictly
sorted; the revoked device can admit no one else, not even a new record with the key of a
sealed device. A revocation by any member counts, and no revocation is ever undone. A
removed device still counts here: one that knows the master password can remove others
through a device that has not heard of its removal yet. The app warns when no active device
removed a device. The warning reads no stamps either, so a removal by a device that was
removed later looks the same, and the app never names a device that is not active.

## Scope

A sync group has one `model.SyncScope`, `APP`: records of the app itself, shared by
linked apps. It syncs four entry kinds, `EMAIL_FORWARDER`, `WORDLIST`, `URL_OVERRIDE` and the
group-internal `OBJECT_KEY`.

No check of an entry depends on the kinds a build knows. A device keeps and relays an entry
of a kind of another scope like one of a kind it does not know, and never writes it to its
records. Pairing codes, the enrollment HELLO and the short authentication string always carry the
scope.

## Sessions

A session runs over a Noise KKhfs stream, which is reliable and ordered: X25519 and an
ephemeral ML-KEM-1024 exchange both key it, so a recorded session stays sealed against a
quantum computer. A message is padded and split into frames of at most 32 KiB, `u8 type |
u8 flags | u32 msgId | payload`. The frames of a message arrive in order, and a frame of a
new message id starts a message. A frame that does not fit ends the session.

1. Each side sends HELLO with the hashes of its roster and envelope. A newer connection of
   a peer replaces the running session only once its HELLO arrives: a replayed handshake
   message completes a handshake, but never brings a message that decrypts.
2. A side sends its roster in a ROSTER message when the peer may not have its hash, always
   before its envelope. The receiver adopts the roster before it applies the entries.
3. An envelope is `{"v": 1, "entries": […]}`; its sender is the peer of the session. Its
   hash is the SHA-256 of its bytes as sent. The receiver acknowledges it by that hash.
4. An empty HEARTBEAT goes out every 10 seconds. A peer silent for 30 seconds is dead.
   Heartbeats name no state: a side is settled only while no ROSTER or ENVELOPE of the
   peer is partly received. A side about to lose its connection, such as before its TURN credentials expire, sends
   BYE with the reason `RECONNECT`; both sides then connect again at once.
5. The receiver asks for the roster and envelope again with RESEND_REQUEST, at most 3
   times per session: when it could not apply them, when it syncs a category again, and
   when writes it dropped as too far in the future are due.

Relay storage carries the same state while the two devices are not online together. Each
direction of a pair has one state item on the relay, `{v, kind, item, from, to, expiresAt,
roster, envelope, missing}`, which the sender replaces. A device derives the item ids and the
sealing keys of a direction from the pair's Noise keys, so the relay can neither read an item
nor move it to another pair or direction. Each item also carries a fresh ML-KEM-1024
encapsulation to the `keys.mlkem1024` of its receiver's record, and its sealing key mixes that secret
in: an item stays sealed as long as either X25519 or ML-KEM holds, and only its receiver opens
it. The receiver checks the id, the direction and the expiry, then merges the roster and
envelope as if a session sent them. An envelope of a
format it does not read, or larger than it takes, counts as none: the roster still counts.
An item lives at most 7 days.

Blobs belong to the group: one blob item per blob, `u8 version | bytes`, version 1. Its id
and sealing key derive from the group's object key, the `OBJECT_KEY` entry, and the blob
hash, so the relay never sees a hash, and an item it swaps does not open. Without an object
key, a device makes no blob request. Any device that holds the blob writes it once, with
`If-None-Match: *`; any device that needs it fetches it and checks the hash. A device writes
each blob it holds that it did not write or find on the relay yet, such as one its user
added, also while offline; it keeps which ones it did, per object key. The blobs it held
when it first used a key count as written. A device that needs a blob the relay lacks names
it in `missing`, and a device that holds it writes it. `missing` names at most 64 blobs: a
reader skips any item that is no blob id, takes the first 64 and ignores the rest. A fetched
item stays on the relay. An item that does not open as its blob, in a format the device
reads, is deleted, and the blob goes into `missing`. An item of a format it does not read, or
larger than it takes, stays.

The group writes a new object key when a device is removed. The removed device never gets
it, so it can't name, open or delete the items written under it. A device uses no key that
leaves a device it knows to be removed uncovered. Items under an old key expire.

A device resumes a blob fetch it could not finish from the bytes it got, with `Range` and
the item's `ETag` in `If-Range`. It keeps those bytes in memory while its vault is unlocked.

The gate of relay storage opens only while every active device lists `relay-storage` in its
record, see `roster.GroupCaps`. Until then, a device that allows it posts a state item with
its roster only, no envelope, so a peer that allows it learns without a session. A device
fetches only while its own user allows it, and writes blobs only while the gate is open. A
device with relay storage off makes no request to it, unless the relay may still hold what
it left there.

## Enrollment

A pairing code is binary, `u8 magic | u8 version | u8 flags | psk (32) | Noise key (32) |
u32 expiry | relay URL | ICE URL | extensions`: the flags hold the member bit and the scope,
the expiry is in seconds since the epoch, and each URL is a `u16` length and UTF-8, empty for
the default. A reader refuses a flag it does not know. The extensions run to the end, at most
256 bytes, each `u8 type | u16 length | value`. A reader skips one it does not know, unless
the high bit of its type marks it critical: then the code needs a newer build. No extension
is defined yet.
Its text is `KGP:` and the uppercase base32 of the bytes, which a QR code holds in its
alphanumeric mode; it is read in either case.

The scanner pins the displayer's Noise key from the code. After the handshake, the
displayer commits to a nonce, the scanner sends its own, and the displayer reveals its
nonce. The 6 digits derive from the handshake and both nonces, so a device in the middle
can not pick its handshake until the digits match. Anyone who saw the code can race the
scanner to the displayer, so the displayer's user types the digits the scanner shows; the
scanner's user then confirms that both show the same. The member signs the admission
when its user confirms and sends the bundle once both users confirmed, but stores the
admission only once the joiner acknowledges the bundle with ENROLL_ACK: a joiner that
cancels before then leaves the roster as it was. If the acknowledgement is lost, or the
member can not store the admission, the joiner holds the group but no device knows it. It
has to leave the link and pair again.

Each ENROLL_HELLO carries the version of its sender and the oldest one it speaks, `v` and
`min`. The devices speak `min(v_a, v_b)` if it is at least `max(min_a, min_b)`; else the
older device needs an update. The member's HELLO names the group; the joiner announces its
record, self-signed for that group, in its next message. The bundle holds the group id and
key, the roster, the entries and the relay. The joiner stores it only if the group id
derives from its founder.

## Blobs

Data too large for an entry, such as the words of a wordlist, travels as a blob. An
entry names its blob by id: the base32 SHA-256 of its bytes. `session.BlobExchange`
moves blobs over a session:

1. Once a session is established, each side sends `BLOB_HAVE` with the blobs it holds.
2. The puller asks for one blob at a time, one window at a time: `BLOB_WANT` with an
   offset.
3. The holder answers with one `BLOB_DATA` window of up to 1 MiB, or with `BLOB_NOPE`.
4. Once the last window arrives, the puller checks the bytes against the id. A transfer
   times out once no frame of its window arrives for 30 seconds, or once a window takes
   2 minutes.

Blob frames share the stream with the sync frames, two sync frames for every blob frame,
so a large blob never stalls an envelope.
