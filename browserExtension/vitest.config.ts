import { defineConfig } from "vitest/config";
import { WxtVitest } from "wxt/testing/vitest-plugin";

export default defineConfig({
  plugins: [WxtVitest()],
  test: {
    include: ["tests/**/*.test.ts"],
    // The first test of a file initializes the 6 MB Wasm.
    testTimeout: 30_000,
  },
});
