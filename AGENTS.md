# Repository guide

- `core/` contains Kotlin pack auditing, optimization, and Bedrock client-data parsing.
- `geyser/` contains the Java Geyser extension, pack selection, and tests.
- `geyser/src/main/resources/extension.yml` defines the extension. Its `main` must name a class in the built JAR.
- `README.md` is the operator guide.

## Verify

- After code changes, run `gradle clean test :geyser:jar`.
- State whether Geyser/Bedrock behavior was tested live. Unit and fake-client tests do not verify rendering.

## Geyser and Bedrock

- Verify Geyser API and protocol behavior in this repository before making claims.
  `MemoryTier` is client-reported, not a hardware measurement. `DeviceOS` is diagnostic only.
- Do not add a companion app or per-player pairing flow.
- Keep resource packs required. Texture estimates are not crash guarantees.

## Packs and data

- Treat source packs as read-only. Store generated variants in Fettle's `pack-cache`.
- Keep Fettle's identity and data separate; do not import another extension's configuration or cache.
- Do not delete packs or caches unless requested. Ask before changing pack selection or stored data when intent is unclear.
