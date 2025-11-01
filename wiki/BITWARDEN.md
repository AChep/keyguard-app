# Bitwarden

### Extra

#### WiFi

The item will show up in a WiFi credentials form based on the following criteria:
- the item is of a login type with a username set, or a custom field with one of the following names:
    - `WiFi SSID`;
    - `SSID`.
- the item has one of the following custom fields:
    - `WiFi Authentication Type` (either `WPA`, `WEP` or `nopass` if the network is open);
    - `WiFi Hidden` (either `true` or `false`);

_The WiFi credentials UI includes a QR code that you can scan with another device to quickly join the network._

#### Tags

A tag is defined by a custom field based on the following criteria:
- the custom field name must be exactly "Tag";
- the custom field value must be visible (not hidden).

The value of this qualifying field is used as the tag's name.
When encoding a tag, the rules do apply.
