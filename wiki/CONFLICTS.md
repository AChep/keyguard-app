# Conflict resolution

> _The conflicts might happen if you edit an item on a device without active internet connection and then 
edit the same item on another device._

Keyguard tries to automatically resolve edit conflicts for the ciphers. It does so by splitting the 
cipher to separate fields and trying to merge then separately:
- if the field is edited on both local and remote, then the remote field wins;
- if the field is edited on either local but not on remote, then the local field wins;
- if the item is added or removed from the list, then Keyguard will try to replicate the action on a new base cipher.
