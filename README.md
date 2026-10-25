# Fettle

Fettle is a Geyser extension that audits Bedrock resource packs, prepares variants, and selects one for each joining player.

## Build and install

Build and test with Java 21:

```bash
./gradlew clean test :geyser:jar
```

Copy `geyser/build/libs/fettle-1.0.1.jar` to Geyser's `extensions` directory. This build targets Geyser API 2.12.0.

## Automatic selection

AUTO uses the Bedrock-reported `MemoryTier` to choose the highest-quality measured variants that fit the required pack set's texture budget. Packs linked by shared geometry use the same profile.

| MemoryTier | Default budget |
| --- | ---: |
| 0-2 | 300 MiB |
| 3 | 450 MiB |
| 4 | 1,200 MiB |

These are Fettle policy values, not an official conversion to Microsoft's performance tiers. AUTO compares the budget against the decoded texture estimate plus the rounded item/block atlas allocations. `MemoryTier` is a client claim, not a measurement of available RAM, GPU, or VRAM; the commonly cited RAM bands are rough estimates, and the exact calculation is unknown ([protocol reference](https://github.com/Sandertv/gophertunnel/blob/master/minecraft/protocol/login/data.go)). Fettle does not use `DeviceOS` to choose quality.

AUTO estimates item/block atlas allocations from referenced textures, sums them across the required-pack stack, rounds the totals up to powers of two, and applies Fettle's conservative 256 MiB per-type stack-atlas policy. Creator Tools validates each pack archive individually; its result does not establish how Bedrock allocates atlases across a multi-pack stack. Fettle keeps the aggregate gate to avoid assuming those per-pack limits can safely be added together. This stack estimate is a Fettle policy, not a verified client runtime limit or crash guarantee.

If the client reports no usable tier, AUTO uses the lowest configured tier budget as a conservative selection cap (300 MiB by default) but keeps `budgetFit` unknown because this is not a hardware measurement. This applies to iOS too: `DeviceOS` cannot tell Fettle how much texture memory that particular device can use, so Fettle does not infer an iOS-specific budget or quality tier. Incomplete fixed-pack texture measurements also keep `budgetFit` unknown; they do not imply that a client needs a lower-quality profile. AUTO will not treat a target variant with incomplete atlas references as a fit. For an incomplete fixed pack, measured atlas bytes are still reserved against the candidate's hard-limit allowance, but the combined atlas fit remains unknown because unmeasured references could add more. If no complete variant fits the selection cap or measured atlas allowance, AUTO chooses the lowest-memory complete set and reports the miss. A pack without a usable variant stays unchanged. Packs remain required.

Fettle does not yet package profiles as Bedrock subpacks. Microsoft's docs describe V2 `memory_tier` as a minimum-RAM requirement in 250 MB steps; the documented automatic choice is for `memory_performance_tier`, which requires manifest V3 and is still labeled experimental ([subpack guide](https://learn.microsoft.com/en-us/minecraft/creator/documents/buildingsubpacks?view=minecraft-bedrock-stable), [manifest format](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/addonsreference/packmanifest?view=minecraft-bedrock-stable)). [Geyser documents subpacks as an API feature](https://github.com/GeyserMC/GeyserWebsite/blob/master/wiki/geyser/packs.md), but Fettle has not verified client-auto selection for required Geyser packs on iOS, so AUTO does not rely on it.

Fettle reads `MemoryTier` from Geyser's session client data through a reflective, version-sensitive path because the public extension API does not expose it as a typed field.

## Profiles

| Profile | Result |
| --- | --- |
| `FULL` | Sends the source pack unchanged. |
| `OPTIMIZE_FULL` | Lossless rewrite. Removes only proven-unused or pixel-identical duplicate assets, crops proven-unused UV regions, and atlases eligible textures with UVs remapped. Pixel-identical textures can be shared across packs only when Geyser/Fettle links those packs to one profile and the full required-pack stack has a complete path/reference scan. PBR texture-set assets, animated textures, alternate-format collisions, and unlinked packs are excluded. It does not downscale textures. |
| `OPTIMIZE_SEMI` | Resizes eligible static textures to 512 px maximum dimension. |
| `OPTIMIZE_MINI` | Resizes eligible static textures to 256 px maximum dimension. |
| `SAFE` | Resizes eligible static textures to 128 px. Font glyph pages are capped at 256 px. |

Resizing reduces detail. Animated sheets, `.png.mcmeta` textures, unsupported formats, and incomplete scans may leave textures above a profile's target. Memory estimates use decoded image dimensions and do not predict crashes.

On the supplied Scaffolding packs, a local `OPTIMIZE_FULL` rewrite measured the non-split pack at 1,176 to 1,116 MiB decoded texture bytes, 1,186 to 828 estimated handles, and 9,097 to 8,739 files in 77s; its archive grew by 580 KB. The split full-animation set measured 3.73 to 2.50 GiB and 1,304 to 661 handles in 157s; its archive grew by 2.29 MiB. The split simple-animation set measured 6.11 to 2.13 GiB and 2,416 to 647 handles in 147s; its archive shrank by 105 KB. These are static pack-audit estimates, not Bedrock runtime memory or proof of crash prevention.

For the measured non-split pack, `SEMI` is about 787 MiB decoded-texture-plus-rounded-atlas memory, within the Tier 4 1,200 MiB budget, but its 269 MiB item atlas exceeds Fettle's 256 MiB stack policy. Therefore `AUTO` selects `MINI` even for a reported Tier 4; increasing the texture budget alone does not change that choice. The [MCTools atlas rule](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/mctoolsvalreference/textureimage?view=minecraft-bedrock-stable) is a validator signal, not a runtime crash guarantee.

For the supplied split sets, Tier 4 selects `MINI` for the linked base/animation pair in the full-animation arrangement (about 486 MiB texture-plus-atlas estimate); the combined `SEMI` atlas estimate is 272 MiB and rounds to 512 MiB. Both `SEMI` packs pass MCTools' individual-pack atlas validation, so the stack-level gate is conservative until its runtime allocation behavior is verified. In the simple-animation arrangement, Tier 4 selects `SEMI` for that pair (about 586 MiB total; 222 MiB raw item atlas rounds to 256 MiB). Creator Tools validation of the selected variants found no `TEXTUREIMAGE407` atlas failures. The overlay packs lack their own `pack_icon.png` (`CPACKICON101`, documented as a warning by the [icon rule](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/mctoolsvalreference/cpackicon?view=minecraft-bedrock-stable)).

Creator Tools also reports `JSON001` for `Scaffolding_interface/sounds/sound_definitions.json`, but the file is valid JSON and uses string paths in `sounds`; Microsoft's [sound guide](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomsounds?view=minecraft-bedrock-stable) documents that format. Fettle leaves this source content unchanged.

Bedrock 1.26.40+ can load baked `__brarchive` resources produced by the official Bedrock Dedicated Server pack optimizer; those clients require at least 1.26.40. Fettle does not decode the archive format, so if one is present it marks texture, atlas, handle, and internal file-count estimates incomplete, skips generated variants, and sends the required original pack rather than reporting a misleading optimization.

Static model cube counts are diagnostics, not crash thresholds. [Microsoft's geometry validator](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/mctoolsvalreference/geometry?view=minecraft-bedrock-stable) reports entity/item geometry counts as informational; its explicit `>50` cube warning applies to custom block geometry. Fettle does not reduce entity model cubes.

## Configuration and commands

Fettle writes `extensions/fettle/config.properties` on first load. Player overrides are stored in `extensions/fettle/profiles.properties`.

```properties
profile=auto
match-name-contains=scaffolding,nexo,mythicmobs,modelengine,geysermodelengine
# Optional budget override in MiB:
# texture-budget-memory-tier-4-mib=1000
```

Admin commands require `fettle.admin`:

- `/fettle device <player|xuid>` shows reported device data, selected-profile pack sizes and total file count, texture-budget fit, and combined stack atlas estimates with Fettle's conservative policy fit; use `/fettle audit` for individual-pack Creator Tools atlas warnings. Incomplete packs include sample unmeasurable texture paths, budget misses list the three largest decoded-texture costs, selected static model complexity and Microsoft’s custom-block `>50`-cube warning are separate from texture budget and live entity counts, and root font glyph pages outside the documented path are identified and counted conservatively.
- `/fettle audit` shows prepared pack archive/file counts, texture and atlas metrics, and the three largest textures by decoded-memory estimate. It flags missing, duplicate, invalid-PNG, or non-square root pack icons, and warns when individual textures exceed Creator Tools' 4 MiB highest-resolution-mip warning budget; validator guidance is not a proven runtime crash threshold. Pack file-count warnings follow Mojang's resource-pack guidance (5,000–7,000 safer; 10,000+ has caused memory failures on some devices), not the separate 2,500-file add-on recommendation.
- `/fettle status` shows the configured profile, cached variant count, live MemoryTier coverage by DeviceOS or Geyser platform when DeviceOS is unavailable, unusable-claim reasons, selected profile sets, and current memory-budget/stack-atlas-policy outcomes by platform, negotiated protocol, client `GameVersion`, reported `GraphicsMode`, tier, and profile. Version, protocol, and graphics mode are diagnostic cohorts only; they do not change pack selection. It also reports selections awaiting Geyser initialization (which includes download, auth, and world setup—not download progress) and since-startup selection attempts, post-initialization joins, pre-initialization disconnects, and resource-pack-reason counters. Historical counters use platform/protocol/graphics/tier/profile cohorts (not client version). A pre-initialization disconnect is not proof of a crash; the `disconnectionScreen.resourcePack` key covers refusals/unknown responses and some transfer errors, not a proven cause or render result.
- `/fettle optimize <auto|full|semi|mini|safe>` changes the global profile.
- `/fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>` sets a player override; `/fettle unset <xuid|online-gamertag>` removes it.
- `/fettle full` selects the original packs; `/fettle reload` reloads configuration.

In `/fettle device`, `clientDataDecoded=false` means Fettle could not decode Geyser's original client-data payload. If it is `true` but `MemoryTier=null`, the payload was decoded but did not contain a usable tier; Fettle still does not infer one from `DeviceOS`.
Automatic no-tier join warnings include `clientDataDecoded` and `DeviceOS`, so operators can distinguish these cases from the server log without running a command for each player.
`MemoryTierClaim` distinguishes `VALID`, `MISSING`, `INVALID`, and `UNDECODABLE`; `/fettle status` groups valid tiers and unusable claims by DeviceOS label/code, falling back to Geyser's Bedrock platform when DeviceOS is unavailable. These aggregates cover current sessions with Fettle-managed packs only and are not persisted.

`profile=full` and `/fettle optimize full` select `OPTIMIZE_FULL`. Use `profile=original` or `/fettle full` for unchanged packs.
Changes to `match-name-contains` require a Geyser restart.

## Pack handling

Source packs are read-only. Generated variants are stored in `extensions/fettle/pack-cache`. Fettle prepares variants when Geyser loads its pack registry; a cold start with large packs can take several minutes. After Scaffolding regenerates a pack, restart Geyser so it loads the new file before Fettle prepares variants. Fettle reports cache use above its 2 GiB advisory threshold but never deletes cache files automatically.

When both `Scaffolding Animated` and `Scaffolding Animated Simple` are registered, Fettle prewarms separate stacks and chooses per connection: Simple for client-reported MemoryTier 0–3 or missing, Full for tier 4+. In the checked split release, Simple has fewer model cubes but more source texture files. This uses a client claim and is a best-effort policy, not a crash guarantee. If the chosen stack is not prewarmed or its pack IDs/content change, Fettle keeps the original required packs. Install one Scaffolding base release: split and non-split distributions can reuse the same manifest UUID, and duplicate UUIDs prevent linked variants from being prepared. Fettle also warns when no prepared profile brings a pack below Mojang's 7,000-file safer guidance.

Generated profile packs use deterministic, profile-specific header UUIDs so Bedrock does not identify different variant contents as the source pack's cached copy. If any required-pack dependency references a target pack UUID, Fettle disables generated profiles for that stack and keeps the original required packs, because changing that UUID could break the dependency.

Optimized linked variants are used only when a session has the same required-pack IDs and contents and target-pack set that Fettle prewarmed. Geyser may enumerate the same set in a different order between startup and session load. If another extension adds, removes, or changes a pack, Fettle logs the mismatch and keeps the original required packs rather than applying a variant built against a different set or texture-path collisions.

Fettle can resize resource-pack textures, but it does not simplify Bedrock models or lower their cube counts. It also does not reduce MythicMobs or ModelEngine entity counts or runtime animation work.
