---
layout: ../layouts/LegalLayout.astro
title: Privacy policy
description: "How Keyguard, its website and license service process and retain personal information."
updated: "2026-11-17"
---

Effective date: November 17, 2026

Artem Chepurnyi (the "operator") provides Keyguard, a commercial password manager. The operator is the data controller for personal information received through Keyguard-controlled services.

This Privacy Policy explains what information is processed, why it is processed, who receives it, how long it is kept, and what rights may apply. It covers the official Keyguard applications, their bundled components, the Keyguard browser extension, the website at `keyguard.dev`, the Keyguard license service at `license.keyguard.dev`, and the Keyguard relay service at `relay.keyguard.dev`.

Most information is processed on your device. When a network feature is used, information may be sent directly to a service you configure or to a provider described below. The operator does not store your vault and does not receive its contents. Device sync uses a relay described in Services You Choose. The relay receives connection metadata and, while relay storage is on, encrypted sync data it cannot read.

Features and data handling may vary by platform, distribution, and configuration.

## Information Stored on Your Device

Keyguard stores a local encrypted copy of your vault. Your local unlock password is used on your device to derive encryption material. It is not written to disk or sent to the operator.

Information stored on your device may include vault contents and attachments, account credentials and tokens, settings, license state, cached information, and diagnostics. Some information required before the vault is unlocked is stored separately and protected by the security controls available on your device.

Biometric information is handled by the operating system. Keyguard receives only the authentication result. Exports, attachments, screenshots, and backups are stored in locations you choose and may not have the same protection as the vault. Automatic backups are not encrypted by Keyguard unless you set a backup password.

The operator does not receive information that remains only on your device.

## Browser Extension

The Keyguard browser extension works with the Keyguard desktop app on the same device. The browser starts Keyguard's connector, which passes messages between the extension and the app. You pair the extension with the app by confirming a code in Keyguard. Messages between the paired extension and the app are encrypted end to end. The extension does not send information to the operator. It contacts no other server, except to load a website's icon in Firefox, as described below.

To suggest and fill logins, the extension reads the addresses of web pages and their frames, and the attributes and labels of form fields. It sends page addresses, and the search terms you enter in its popup, to the app. The app answers with the names, usernames, and account names of matching items. Passwords and one-time codes reach the extension only to fill or copy them. Depending on your approval settings, Keyguard asks you to approve the request first. For passkey requests, the extension passes the website's request and its origin to the app, and returns the app's answer to the website.

To offer to save a login, the extension checks on your device when you sign in or sign up. When you submit a form with a password you typed, it reads that password and the username. It also reads them when the page goes away within 5 seconds after you click or press Enter in that form, for example when you select Cancel and leave the page. A username you submit on its own, as on the first step of a sign-in, is kept for the password step. The extension keeps this information in memory for a few minutes and sends it to the app only if you select Save. The extension never saves a login itself.

The extension stores its settings and pairing keys in the browser's storage for the extension. For a few minutes after a fill, it keeps a reference to the filled item in memory, to offer it on the next sign-in step. When you copy a value, the extension writes it to the clipboard. The app keeps a log of the extension's requests on your device, with the website, the type of request, and the time. The app does not log requests from private browser windows, and they do not change the item's usage history. You can review the log and set how long it is kept in Keyguard's settings.

The extension shows the icon of the current website. In Chrome and other Chromium browsers, the icon comes from the browser's own cache. In Firefox, the extension may load it from the address the website sets for its icon, such as the website itself or a content delivery network. If you grant the optional privacy permission in the extension's options, the extension turns off the browser's own password saving.

The operator does not receive information through the extension. The extension contains no analytics, advertising, or remote code. Information it handles is used only to provide its features. It is not sold, used for advertising, or transferred to third parties, except for the icon requests in Firefox described above.

## Services You Choose

Keyguard can connect directly to services and storage locations you choose, including Bitwarden, Vaultwarden, KeePass files, and WebDAV. The application sends the account information, authentication material, and content required to perform the requested operation. Vault content is encrypted before synchronization where required by the selected protocol. The selected service may still receive account, authentication, protocol, and network information.

The operator does not proxy, receive, or store this traffic. Remote services process and retain information under their own privacy policies.

Keyguard also supports the following network features:

- Have I Been Pwned's Pwned Passwords service receives the first five hexadecimal characters of a password's SHA-1 hash. The password and complete hash are not sent. Other breach searches may send a complete username or email address and the required service credential.
- Gravatar may receive a deterministic hash derived from an email address.
- An icon provider or saved website may receive a domain or URL stored in your vault.
- Services you configure for email relay, public keys, application information, or related sites receive the credentials, identifiers, and request content required for the requested operation.
- Platform services may receive device, network, service, and account information required for paired-device, credential-provider, and AutoFill features.
- Linked devices sync between your devices through a relay and, when devices cannot connect directly, a TURN server. Unless you configure your own in the relay settings of the link, the operator runs them. They receive IP addresses, connection times, the sizes of encrypted messages, a room identifier for each pair of linked devices, which rooms each connection joins, and a random identifier for each connection. One connection of a device joins the rooms of all its pairs, so the relay can tell which rooms belong to one device. Devices encrypt sync traffic end to end, so the relay and the TURN server do not receive synced data, device names, or account information. This information is used only to connect your devices and to prevent abuse. The relay keeps this information in memory only. Relay storage is on by default, and you can turn it off in the relay settings of the link. While it is on, the relay also stores encrypted sync data on disk, so that a device that is offline can collect it later. Until every linked device has it on, this data is limited to the list of linked devices. Each stored item is encrypted so that only your linked devices can read it: the relay cannot read or change it, and stored items contain no IP addresses. The relay deletes stored items after at most 7 days, or earlier when it needs disk space. IP-based abuse-prevention records can remain after a connection closes, until later cleanup or a relay restart. The TURN server records IP addresses in operational logs, which follow the configured hosting retention settings.

Password-breach checks, avatars, and website icons are enabled by default where supported. You can manage these features in Keyguard's settings. Disabling a feature stops future requests but does not delete information already retained by a recipient.

Relevant third-party privacy policies include:

- [Bitwarden Privacy Policy](https://bitwarden.com/privacy/)
- [Have I Been Pwned Privacy Policy](https://haveibeenpwned.com/Privacy)
- [Automattic Privacy Policy for Gravatar](https://automattic.com/privacy/)
- [Apple Privacy Policy](https://www.apple.com/legal/privacy/)
- [Google Privacy Policy](https://policies.google.com/privacy)

Other configured or self-hosted services are governed by the policies of their operators.

## Information Received by the Operator

Keyguard-controlled services receive personal information for purchases and license validation, website and service operation, device sync connections as described in Services You Choose, optional crash reporting, and messages you send to the operator.

### License Data

To issue or validate an entitlement, Keyguard sends a store-issued purchase proof or a Keyguard license key to `license.keyguard.dev`, together with product and request-security information required for validation. Apple or Google may provide purchase and transaction information during verification. The operator receives a linked license identifier and entitlement state, but not full payment-card numbers or security codes.

Paddle handles website payments as merchant of record. It processes contact, billing, payment, and technical information under its [Privacy Policy](https://www.paddle.com/legal/privacy). Purchase information is available to the operator for fulfillment and support.

The license service receives payment and refund notifications from Paddle. These can include customer references and limited payment details, such as a cardholder name, card ending and expiry, or PayPal email address. The license database stores checkout and transaction identifiers, a hash of the private recovery token, timestamps, amounts, payment and refund status, and linked license records. It does not store the full notification or its billing-contact and payment-method fields.

For supported Ko-fi purchases, the license service receives purchase details and the buyer's email address. It stores a hash of the email address with purchase and license records.

License Data is used to provide the requested purchase or entitlement and for the legitimate interest of preventing fraud and abuse. The license service is hosted using Google Firebase and Google Cloud. License, checkout, and transaction records are retained indefinitely. There is no automatic or self-service deletion process. Checkout recovery tokens expire after seven days; this does not delete the associated records.

### Website and Service Data

GitHub Pages hosts `keyguard.dev`. GitHub records visitors' IP addresses for security, including visitors without a GitHub account. See [GitHub's Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

The website saves your theme preference in browser local storage. Checkout uses tab session storage to retain a transaction reference and private recovery token so you can resume checkout or retrieve your license after a refresh. You can clear this storage through your browser, which also removes the saved recovery information.

Visiting the purchase page loads Paddle and its supporting services to preview prices and support checkout. Paddle uses your IP address to estimate your location for pricing and taxes. Its browser storage and data processing are covered by its Privacy Policy linked above.

Hosting providers process network and request data, including IP addresses and operational logs, to operate and secure the website and license service. This processing serves the legitimate interest of maintaining reliable, secure services. Logs follow the configured hosting retention settings.

### Optional Crash Data

Some Keyguard distributions include optional crash reporting through Google Firebase Crashlytics. Crash reporting is off until you enable it. When enabled, Firebase may receive crash traces, relevant application state and diagnostic logs, application and device information, and identifiers generated for crash reporting. This information is used with your consent to diagnose errors.

You can disable crash reporting in Keyguard's settings. [Firebase states](https://firebase.google.com/support/privacy) that Crashlytics retains crash information and associated identifiers for 90 days before beginning removal from live and backup systems. The operator does not keep a separate server-side copy. An operating system or app store may collect diagnostics independently under its own privacy policy.

### Communications Data

When you send a support, privacy, or security message, the operator receives the information you include and the contact information required to reply. This information is used to respond to your request and for legitimate interests in troubleshooting, security, recordkeeping, and establishing or defending legal claims, subject to applicable law.

Messages may be retained indefinitely. You may contact the operator about a message, and the request will be assessed under applicable law.

## Service Providers and Disclosures

Google Firebase and Google Cloud process License Data and optional Crash Data to provide hosting, validation, security, and crash-reporting functions for the operator. The [Firebase Data Processing and Security Terms](https://firebase.google.com/terms/data-processing-terms) apply to this processing.

The operator may disclose personal information to service providers acting on the operator's instructions, when required by law or a valid legal request, to protect Keyguard and its users from fraud or security threats, or to establish, exercise, or defend legal claims.

Service providers may process information in countries other than the country where you live. Where required, international transfers are handled under applicable law and the relevant provider terms.

The operator does not sell or rent personal information. Personal information is not used for advertising, third-party marketing, profiling, or tracking across other companies' applications or websites.

## Retention and Deletion

Information stored by Keyguard on your device normally remains until you remove it, erase Keyguard's data, or uninstall the application. Deleting information from Keyguard does not delete remote accounts, information held by third-party services, user-selected files, exports, backups, or operating-system backups. You must delete that information through the relevant service, storage location, or operating-system controls.

Retention periods for information received by the operator are stated in the relevant sections above. Other services retain information under their own policies.

## Your Choices and Rights

You can manage supported network features and optional crash reporting in Keyguard's settings. Requests about information held only by a service you chose must be directed to that service.

Depending on where you live, you may have rights to access, correct, delete, restrict, or receive a copy of your personal information, object to certain processing, withdraw consent, and complain to a data-protection authority. These rights may be subject to legal exceptions. Withdrawing consent does not affect processing that occurred before withdrawal.

To exercise a right concerning information controlled by the operator, use the contact address below. The operator may request information needed to verify your identity and will respond as required by applicable law.

## Security

Keyguard uses safeguards appropriate to the information it handles. These include local vault encryption, password-based key derivation, operating-system protected storage where available, platform access controls, and encrypted transport for Keyguard-controlled services and official provider endpoints.

No method of electronic storage or transmission is completely secure. Absolute security cannot be guaranteed.

## Children's Privacy

Keyguard is not directed to children and does not knowingly collect personal information from children through Keyguard-controlled services where parental consent is required. If this occurs, contact the operator so the information can be investigated and deleted where required.

## Changes to This Privacy Policy

This Privacy Policy may be updated when Keyguard, its integrations, or legal requirements change. The revised policy will be published with a new "Last updated" date. When practicable, notice of a material change will be provided before it takes effect through the application or release notes.

## Contact

For privacy questions, requests, or complaints, contact the data controller:

Artem Chepurnyi<br>
[keyguard@artemchep.com](mailto:keyguard@artemchep.com)<br>
Lviv, Ukraine
