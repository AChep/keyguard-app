import { mount } from "svelte";
import { browser } from "wxt/browser";
import type { ObservableStorageArea } from "../../src/lib/options/store";
import { localizePage } from "../../src/lib/ui/pageLocale";
import { followTheme } from "../../src/lib/ui/storedTheme";
import App from "./App.svelte";
import "./style.css";

localizePage(document);
// Firefox: the colors of the browser's theme, from the first paint.
if (import.meta.env.FIREFOX) followTheme(window, browser.storage.local as unknown as ObservableStorageArea);

mount(App, { target: document.getElementById("app")! });
