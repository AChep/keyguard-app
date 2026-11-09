# syntax=docker/dockerfile:1

# Builds a static musl binary on the build machine's own architecture and
# cross-links it for the target platform with rust-lld (the relay has no C
# dependencies), so `--platform linux/amd64` on an arm64 host needs no emulation.
FROM --platform=$BUILDPLATFORM rust:1.98-slim-trixie@sha256:4cd829461bd5c4d511c32e269da9cb8929223b666519d8004e35fc8d1d771ab7 AS build
ARG TARGETARCH
WORKDIR /src
RUN case "$TARGETARCH" in \
      amd64) echo x86_64-unknown-linux-musl ;; \
      arm64) echo aarch64-unknown-linux-musl ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac > /target && rustup target add "$(cat /target)"
COPY Cargo.toml Cargo.lock ./
COPY src ./src
ENV RUSTFLAGS="-C linker=rust-lld -C target-feature=+crt-static"
# The platform builds run at once and share these caches, but Cargo's package
# lock is outside the mount: lock the caches, so they take turns.
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/src/target,sharing=locked \
    cargo build --release --locked --bin keyguard-relay --target "$(cat /target)" \
 && cp "target/$(cat /target)/release/keyguard-relay" /keyguard-relay
# The final image has no shell: make the data directory here.
RUN mkdir /data

FROM gcr.io/distroless/static-debian13:nonroot@sha256:18f4cc26c9ffa7f20a359298be7deadac5c607c8e03a02b8119f1a5fc2f82c99
COPY --from=build /keyguard-relay /usr/local/bin/keyguard-relay
# For RELAY_MAILBOX_DIR=/data. A new named volume copies the owner from here.
COPY --from=build --chown=nonroot:nonroot /data /data
USER nonroot:nonroot
EXPOSE 7400
ENTRYPOINT ["/usr/local/bin/keyguard-relay"]
