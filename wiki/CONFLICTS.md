# Conflict resolution

> _The conflicts might happen if you edit an item on a device without an active internet connection and then
edit the same item on another device._

Keyguard tries to automatically resolve edit conflicts for the ciphers. It does so by splitting the 
cipher to separate fields and trying to merge them separately:
- if the field is edited on both local and remote, then the remote field wins;
- if the field is edited either locally but not remotely, then the local field wins;
- if the item is added or removed from the list, then Keyguard will try to replicate the action on a new base cipher.

## Example

- If you edit the Username on one device and the Password on the other device, then it will handle the change just fine, merging both changes together.

- If you edit the Username on one device and the same Username on the other device, then the change from the server will win.

- If you add a Custom field on one device and another Custom field on another device, then it will merge them correctly: you will now have both Custom fields.
