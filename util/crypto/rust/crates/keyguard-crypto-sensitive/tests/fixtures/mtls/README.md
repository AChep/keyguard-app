These synthetic client identities are test data.

- `client.pkcs12.der`: password `changeit`.
- `client-empty-pass.pkcs12.der`: empty password.
- `client-no-mac.pkcs12.der`: the empty-password fixture with its MAC removed,
  exercising compatibility with unprotected Java keystores.
- `client-ec-chain.pkcs12.der`: empty-password P-256 client identity and its test CA.
