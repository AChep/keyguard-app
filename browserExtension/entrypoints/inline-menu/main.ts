// The inline menu: an extension page that a content script embeds next to a
// login field. It shows matches and acts on trusted clicks; values go from the
// background to the content script, never through this page.
import { MENU_PORT } from "../../src/lib/page/messages";
import { mountEmbedded } from "../../src/lib/ui/embedded";
import App from "./App.svelte";

// The content script keeps the frame hidden until the menu tells its height.
mountEmbedded(App, MENU_PORT, { hidden: true });
