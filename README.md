# Fettle

Fettle is a Geyser extension that audits Bedrock resource packs, prepares variants, and selects one for each joining player.

## Build and install

Build and test with Java 21:

```bash
./gradlew clean test :geyser:jar
```

Copy `geyser/build/libs/fettle-1.0.0.jar` to Geyser's `extensions` directory. This build targets Geyser API 2.12.0.

## Automatic selection

AUTO uses the Bedrock-reported `MemoryTier` to choose the highest-quality measured variants that fit the required pack set's texture budget. Packs linked by shared geometry use the same profile.

| MemoryTier | Default budget |
| --- | ---: |
| 0-2 | 300 MiB |
| 3 | 450 MiB |
| 4 | 1,200 MiB |

These are Fettle policy values, not an official conversion to Microsoft's performance tiers. `MemoryTier` is a client claim, not a measurement of available RAM, GPU, or VRAM. Fettle does not use `DeviceOS` to choose quality.

If the client reports no usable tier, AUTO uses the highest-quality available profile, usually the original pack. If every complete variant exceeds the budget, AUTO chooses the lowest-memory complete set. Incomplete measurements make `budgetFit` unknown; a pack without a usable variant stays unchanged. Packs remain required.

Fettle reads `MemoryTier` from Geyser's session client data through a reflective, version-sensitive path because the public extension API does not expose it as a typed field.

## Profiles

| Profile | Result |
| --- | --- |
| `FULL` | Sends the source pack unchanged. |
| `OPTIMIZE_FULL` | Lossless rewrite. Removes only proven-unused or duplicate assets, crops proven-unused UV regions, and atlases eligible textures with UVs remapped. It does not downscale textures. |
| `OPTIMIZE_SEMI` | Resizes eligible static textures to 512 px maximum dimension. |
| `OPTIMIZE_MINI` | Resizes eligible static textures to 256 px maximum dimension. |
| `SAFE` | Resizes eligible static textures to 128 px. Font glyph pages are capped at 256 px. |

Resizing reduces detail. Animated sheets, `.png.mcmeta` textures, unsupported formats, and incomplete scans may leave textures above a profile's target. Memory estimates use decoded image dimensions and do not predict crashes.

## Configuration and commands

Fettle writes `extensions/fettle/config.properties` on first load. Player overrides are stored in `extensions/fettle/profiles.properties`.

```properties
profile=auto
match-name-contains=scaffolding,nexo,mythicmobs,modelengine,geysermodelengine
# Optional budget override in MiB:
# texture-budget-memory-tier-4-mib=1000
```

Admin commands require `fettle.admin`:

- `/fettle device <player|xuid>` shows reported device data, selected profiles, and estimates.
- `/fettle audit` shows prepared pack metrics.
- `/fettle status` shows the configured profile and cached variant count.
- `/fettle optimize <auto|full|semi|mini|safe>` changes the global profile.
- `/fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>` sets a player override; `/fettle unset <xuid|online-gamertag>` removes it.
- `/fettle full` selects the original packs; `/fettle reload` reloads configuration.

`profile=full` and `/fettle optimize full` select `OPTIMIZE_FULL`. Use `profile=original` or `/fettle full` for unchanged packs.
Changes to `match-name-contains` require a Geyser restart.

## Pack handling

Source packs are read-only. Generated variants are stored in `extensions/fettle/pack-cache`. Fettle prepares variants when Geyser loads its pack registry; a cold start with large packs can take several minutes. After Scaffolding regenerates a pack, restart Geyser so it loads the new file before Fettle prepares variants.

Fettle changes resource packs only. It does not reduce MythicMobs or ModelEngine entity counts or runtime animation work.
