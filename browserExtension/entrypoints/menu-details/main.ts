// The card beside the inline menu: an extension page that a content script
// embeds next to the menu. It shows the details of the login whose row is
// selected, which only the background sends it, and copies its fields: its
// clicks go through a click gate, and the background's checks, as the menu's.
import { MENU_DETAILS_PORT } from "../../src/lib/page/messages";
import { mountEmbedded } from "../../src/lib/ui/embedded";
import App from "./App.svelte";

// The content script shows the frame once the background passed on its height.
mountEmbedded(App, MENU_DETAILS_PORT, { hidden: true });
