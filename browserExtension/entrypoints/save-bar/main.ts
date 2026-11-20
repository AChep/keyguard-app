// The save bar: an extension page that the top frame's content script embeds
// after a sign-in or sign-up with a new password. It never sees the password;
// Save asks the background, which sends `save` to the app.
import { SAVE_BAR_PORT } from "../../src/lib/page/messages";
import { mountEmbedded } from "../../src/lib/ui/embedded";
import App from "./App.svelte";

mountEmbedded(App, SAVE_BAR_PORT);
