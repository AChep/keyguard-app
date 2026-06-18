---
title: QR codes & barcodes
description: Render any field as a scannable QR code or barcode, and scan codes back in with the camera or an image.
category: reference
order: 8
---

Keyguard can turn a value into a scannable code so another device can read it
off your screen, and — on mobile — read a code back in from the camera or an
image. This is what powers conveniences like scanning a TOTP key, sharing
WiFi credentials or bringing your discount codes to a shop.

## Show a value as a code

Pick **Show in barcode** on a field or value to render it as an image you can
hold up to another device. By default the value is encoded as a **QR code**.

A format picker on the screen lets you switch the encoding between:

- **QR Code** (default) — 2D, the most widely scannable;
- **Code 128** — 1D;
- **Code 93** — 1D;
- **Code 39** — 1D;
- **PDF417** — stacked 2D, used on IDs and boarding passes.

The format you choose is remembered for next time per value. While the code is on
screen, Keyguard **keeps the display awake** so it doesn't dim or lock mid-scan.

Anything longer than about **1024 characters** can't be rendered, 
so the **Show in barcode** option simply won't appear for oversized fields.
