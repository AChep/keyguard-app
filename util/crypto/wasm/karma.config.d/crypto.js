const setup = process.env.KEYGUARD_CRYPTO_TEST_SETUP;
// Resolve from Kotlin's installed npm workspace. The source package.json beside
// the setup script otherwise makes its self-reference select an unbuilt adapter.
config.webpack.resolve.alias = {
    ...config.webpack.resolve.alias,
    'keyguard-crypto-wasm$': require.resolve('keyguard-crypto-wasm', { paths: [config.basePath] }),
};
config.files.unshift(setup);
config.preprocessors[setup] = ['webpack'];
config.client.mocha = { ...config.client.mocha, timeout: 180_000 };
config.browserNoActivityTimeout = 240_000;
