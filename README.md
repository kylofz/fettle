# Fettle

Fettle is a Geyser extension that audits Bedrock resource packs, builds optimized copies, and chooses a profile for each joining player.

## Build and install

Build and test with Java 21:

```bash
./gradlew clean test :geyser:jar
```

Copy `geyser/build/libs/fettle-1.0.3.jar` to Geyser's `extensions` directory. The build targets Geyser API 2.12.0.

## Automatic selection

`AUTO` uses the Bedrock client's reported `MemoryTier` to choose the highest-quality prepared profiles that fit the configured texture budget. Packs linked by shared geometry use the same profile.

| MemoryTier | Default budget |
| --- | ---: |
| 0–2 | 300 MiB |
| 3 | 450 MiB |
| 4 | 1,200 MiB |

These budgets are Fettle settings, not an official mapping to Microsoft's performance tiers. A `MemoryTier` is a client claim, not a measurement of RAM, GPU, or VRAM. Common RAM ranges are rough estimates, and the client's calculation is unknown. Fettle uses `DeviceOS` for diagnostics only.

Fettle estimates decoded texture memory and item/block atlas allocations across the required pack set. It rounds each aggregate atlas estimate up to a power of two and applies a conservative 256 MiB limit to each atlas type. Creator Tools checks packs individually, so its results do not confirm how Bedrock allocates atlases across several packs. Fettle's combined limit is a policy, not a verified client limit or crash guarantee.

If a client reports no usable tier, `AUTO` uses the lowest configured tier budget as a conservative cap (300 MiB by default) and marks budget fit as unknown. Fettle does not infer an iOS budget from `DeviceOS`. Incomplete measurements also leave budget fit unknown. `AUTO` will not count a target variant with incomplete atlas references as a fit. For a fixed pack with an incomplete scan, it reserves the measured atlas bytes but cannot confirm the combined atlas fit. If no complete profile fits the cap or atlas limit, `AUTO` chooses the lowest-memory complete set and reports the miss. A pack without a usable variant stays unchanged. Packs remain required.

Fettle does not package profiles as Bedrock subpacks. Bedrock's V2 `memory_tier` field sets a minimum RAM requirement in 250 MB steps. Microsoft's documented automatic selection uses `memory_performance_tier`, which requires manifest V3 and remains experimental. Geyser exposes a subpack API, but Fettle has not verified automatic selection of required packs on iOS.

Fettle reads `MemoryTier` from Geyser's session client data through reflection because Geyser's public extension API has no typed field for it. This path may need updates when Geyser changes.

## Profiles

| Profile | What it does |
| --- | --- |
| `FULL` | Sends the source pack unchanged. |
| `OPTIMIZE_FULL` | Rewrites the pack without reducing texture dimensions. It removes only assets proven unused or pixel-identical, crops unused UV regions, and atlases eligible textures with UVs remapped. Cross-pack texture sharing requires linked packs and a complete scan of the full required pack set. PBR texture sets, animated textures, format collisions, and unlinked packs are excluded. |
| `OPTIMIZE_SEMI` | Resizes eligible static textures to a maximum dimension of 512 px. |
| `OPTIMIZE_MINI` | Resizes eligible static textures to a maximum dimension of 256 px. |
| `SAFE` | Resizes eligible static textures to 128 px. Font glyph pages are capped at 256 px. |

Resizing loses detail. Animated sheets, `.png.mcmeta` textures, unsupported formats, and incomplete scans can leave some textures above the target. Memory estimates use decoded image dimensions; they do not predict Bedrock crashes.

### Scaffolding pack measurements

These `OPTIMIZE_FULL` results come from static audits of the supplied packs:

| Pack set | Decoded texture estimate | Texture handles | Files | Time | Archive size change |
| --- | ---: | ---: | ---: | ---: | ---: |
| Non-split | 1,176 → 1,116 MiB | 1,186 → 828 | 9,097 → 8,739 | 77 s | +580 KB |
| Split, full animation | 3.73 → 2.50 GiB | 1,304 → 661 | — | 157 s | +2.29 MiB |
| Split, simple animation | 6.11 → 2.13 GiB | 2,416 → 647 | — | 147 s | −105 KB |

These are pack estimates, not Bedrock runtime measurements or proof that a crash is fixed.

For the measured non-split pack, `SEMI` estimates about 787 MiB for textures and rounded atlas allocations. Its item atlas estimate is 269 MiB, over Fettle's 256 MiB per-type policy, so `AUTO` chooses `MINI` even at MemoryTier 4. Raising the texture budget alone will not change that choice. Creator Tools' atlas check is a validator signal, not a runtime crash limit.

For the supplied split packs, MemoryTier 4 selects `MINI` for the linked base/full-animation pair (about 486 MiB total). `SEMI` has a combined atlas estimate of 272 MiB, which rounds to 512 MiB. Both `SEMI` packs pass Creator Tools' individual atlas check, so Fettle's combined limit remains conservative until client allocation is verified. With the simple-animation pack, `AUTO` selects `SEMI` (about 586 MiB total); its 222 MiB item atlas rounds to 256 MiB. Creator Tools found no `TEXTUREIMAGE407` atlas failures in the selected variants. The overlay packs lack `pack_icon.png`, which triggers the documented `CPACKICON101` warning.

Creator Tools reports `JSON001` for `Scaffolding_interface/sounds/sound_definitions.json`. The file is valid JSON, and its `sounds` values use string paths as shown in Microsoft's [custom sounds guide](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomsounds?view=minecraft-bedrock-stable). Fettle leaves the file unchanged.

Bedrock 1.26.40 and later can load baked `__brarchive` resources made by the official Bedrock Dedicated Server pack optimizer. Fettle cannot read that archive format. If a pack contains one, Fettle marks its texture, atlas, handle, and file-count estimates incomplete, skips generated variants, and sends the original required pack.

Model cube counts are diagnostics, not crash thresholds. Microsoft's geometry validator reports entity and item counts as information. Its explicit warning above 50 cubes applies to custom block geometry. Fettle does not reduce entity model cubes.

## Configuration and commands

Fettle creates `extensions/fettle/config.properties` on first load. Player overrides go in `extensions/fettle/profiles.properties`.

```properties
profile=auto
match-name-contains=scaffolding,nexo,mythicmobs,modelengine,geysermodelengine
# Optional budget override in MiB:
# texture-budget-memory-tier-4-mib=1000
```

Admin commands require `fettle.admin`:

- `/fettle device <player|xuid>` shows a player's reported device data, selected profiles, pack sizes, texture estimate, and atlas estimates. It also lists incomplete measurements and the largest texture costs. Use `/fettle audit` for pack-specific warnings. Model counts are separate from texture estimates and live entity counts.
- `/fettle audit` shows cached pack sizes, file and texture counts, atlas estimates, and the largest textures. It reports icon problems, textures over Creator Tools' 4 MiB highest-resolution-mip warning budget, and file-count guidance. Mojang's resource-pack guidance treats 5,000–7,000 files as safer; packs over 10,000 files have caused memory failures on some devices. This differs from the 2,500-file add-on recommendation.
- `/fettle status` shows the configured profile, cached variants, current MemoryTier coverage, profile selections, and budget/atlas results by platform, protocol, game version, graphics mode, tier, and profile. It also shows startup counts for pack-selection attempts, Geyser initialization joins, and disconnects. These counts do not prove that a client rendered the pack. A disconnect before initialization is not proof of a crash. The `disconnectionScreen.resourcePack` reason includes refusals, unknown responses, and some transfer errors. Historical counts are grouped by platform, protocol, graphics mode, tier, and profile, not client version.
- `/fettle optimize <auto|full|semi|mini|safe>` sets the global profile.
- `/fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>` sets a player's profile. `/fettle unset <xuid|online-gamertag>` removes it.
- `/fettle full` sends the original packs. `/fettle reload` reloads the configuration.

In `/fettle device`, `clientDataDecoded=false` means Fettle could not decode Geyser's client-data payload. If it is `true` while `MemoryTier=null`, the payload had no usable tier. Fettle does not infer one from `DeviceOS`. The server log records `clientDataDecoded` and `DeviceOS` for automatic selections without a usable tier.

`MemoryTierClaim` reports `VALID`, `MISSING`, `INVALID`, or `UNDECODABLE`. `/fettle status` groups current sessions with Fettle-managed packs by DeviceOS, or by Geyser's Bedrock platform when DeviceOS is unavailable. These counts are not saved across restarts.

`profile=full` and `/fettle optimize full` select `OPTIMIZE_FULL`. Use `profile=original` or `/fettle full` to send unchanged packs. Changes to `match-name-contains` require a Geyser restart.

## Pack handling

Fettle reads source packs without changing them. It stores generated variants in `extensions/fettle/pack-cache` and prepares them when Geyser loads its pack registry. A cold start with large packs can take several minutes. After Scaffolding regenerates a pack, restart Geyser so Fettle reads the new file. Fettle warns when the cache exceeds 2 GiB, but never deletes cache files automatically.

When both `Scaffolding Animated` and `Scaffolding Animated Simple` are registered, Fettle prepares separate pack sets. It sends Simple to clients reporting MemoryTier 0–3 or no tier, and Full to clients reporting tier 4 or higher. In the checked split release, Simple has fewer model cubes but more source texture files. This choice uses client-reported data and cannot guarantee that a client will avoid a crash. If the selected pack set was not prepared or its IDs/content changed, Fettle keeps the original required packs. Install one Scaffolding base release. Split and non-split distributions can reuse a manifest UUID, and duplicate UUIDs prevent Fettle from preparing linked variants. Fettle warns if no profile brings a pack below Mojang's 7,000-file safer guidance.

Generated variants use deterministic header UUIDs that differ by profile, so Bedrock does not reuse the source pack's cached copy for different contents. If a required pack depends on a target pack's UUID, Fettle disables generated variants for that pack set. Changing the UUID could break the dependency.

Fettle uses optimized linked variants only when the session has the same required-pack IDs and contents and the same target packs as the prepared set. Pack order can differ. If another extension adds, removes, or changes a pack, Fettle keeps the original required packs to avoid using a variant built for a different set or conflicting texture paths.

Fettle resizes textures. It does not simplify Bedrock models, reduce MythicMobs or ModelEngine entity counts, or reduce runtime animation work.
