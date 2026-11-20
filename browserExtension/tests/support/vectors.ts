import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export interface VectorMessage {
  sender?: "initiator" | "responder";
  payload: string;
  ciphertext: string;
  text?: string;
}

export interface KeyguardVector {
  protocol_name: string;
  origin: string;
  init_prologue: string;
  init_static: string;
  init_static_public: string;
  resp_static: string;
  resp_static_public: string;
  handshake_hash: string;
  messages: VectorMessage[];
  pairing: {
    extension_nonce: string;
    commit: string;
    app_nonce: string;
    sas_info: string;
    sas_okm: string;
    code: string;
  };
}

/** protocol/vectors/noise_xx.json, shared with the Rust and Kotlin tests. */
export const vectors = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../protocol/vectors/noise_xx.json", import.meta.url)), "utf8"),
) as { keyguard: KeyguardVector };
