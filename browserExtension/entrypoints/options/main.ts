import { mount } from "svelte";
import { localizePage } from "../../src/lib/ui/pageLocale";
import App from "./App.svelte";
import "./style.css";

localizePage(document);

mount(App, { target: document.getElementById("app")! });
