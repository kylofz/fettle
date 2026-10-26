# Fettle

Fettle is a Geyser extension that audits Bedrock resource packs, builds optimized copies, and chooses a profile for each joining player.

## Build and install

Build and test with Java 21:

```bash
./gradlew clean test :geyser:jar
```

Copy `geyser/build/libs/fettle-1.0.7.jar` to Geyser's `extensions` directory. The build targets Geyser API 2.12.0.

## Automatic selection

`AUTO` uses the Bedrock client's reported `MemoryTier` to choose the highest-quality prepared profile combination that fits the configured decoded-texture budget, Fettle's separate item/block atlas policy, its 10,000-file stack limit, and its 4,000 estimated-texture-handle policy. Packs linked by shared geometry use the same profile. A candidate also needs complete texture-handle references and a complete bounded model scan. If no validated candidate fits or the stack cannot be measured, Fettle fails the resource-pack event instead of sending an unverified fallback. These estimates cannot guarantee that a client will load or render a pack. The selector bounds its Pareto search to 4,096 states; if the complete search exceeds that bound it reports `SEARCH_LIMIT` and does not select a partial result.

| MemoryTier | Default budget |
| --- | ---: |
| 0–2 | 300 MiB |
| 3 | 450 MiB |
| 4 | 1,200 MiB |

These budgets are Fettle settings, not an official mapping to Microsoft's performance tiers. A `MemoryTier` is a client claim, not a measurement of RAM, GPU, or VRAM. Common RAM ranges are rough estimates, and the client's calculation is unknown. Fettle uses `DeviceOS` for diagnostics only.

Fettle estimates decoded texture memory and item/block atlas allocations across the required pack set. It rounds each aggregate atlas estimate up to a power of two and applies a conservative 256 MiB limit to each atlas type. The AUTO selector also counts total file entries across the stack; this deliberately counts overlapping files in separate packs because Fettle cannot establish Bedrock's complete effective-resource loading behavior. It reports uncompressed bytes, referenced-texture estimates, model counts and cube counts separately. Microsoft's 800-handle recommendation applies to an add-on pack; Fettle reports it per pack as guidance. AUTO uses a separate 4,000 estimated-handle ceiling, Microsoft's documented engine limit, but the estimate excludes vanilla resources and can count overridden paths more than once. Model and animation work have no verified device-specific budget here, so those measurements do not predict render speed or crashes. Creator Tools checks packs individually, so its results do not confirm how Bedrock allocates atlases across several packs. Fettle's combined limits are policies, not verified client limits or crash guarantees.

If a client reports no usable tier, `AUTO` uses `texture-budget-unknown-mib` when set. Otherwise it uses the smaller of 300 MiB and the lowest configured tier budget. A tier 4 override alone cannot raise the unknown-device cap above 300 MiB. This cap is a policy choice, not a device measurement. Fettle does not infer an iOS budget from `DeviceOS`. Incomplete texture or atlas measurements cannot qualify a variant. If no validated profile fits, or the stack cannot be measured, Fettle fails the session's resource-pack event and Geyser disconnects that connection before sending pack info. Packs remain required.

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

The opt-in `PackBenchmarkTest` runs the `SAFE` rewrite and reports audit time, optimizer time, process CPU time, sampled peak heap, archive sizes, texture and atlas estimates, handles, file counts, and transformation counts. Give it local pack paths with `FETTLE_BENCHMARK_PACKS`, separated by the platform path separator. It writes outputs only in Gradle's temporary test directory.

```bash
FETTLE_BENCHMARK_PACKS="/packs/scaffolding.mcpack:/packs/scaffolding-split.mcpack" \
  ./gradlew :core:test --tests me.kylofz.fettle.optimizer.PackBenchmarkTest
```

For the measured non-split pack, `AUTO` chooses `MINI` (117 MiB estimated decoded texture bytes; 114 MiB raw item-atlas bytes, rounded to 128 MiB). `SEMI` has 275 MiB decoded texture bytes and a 269 MiB item-atlas estimate, which rounds to 512 MiB and exceeds Fettle's separate 256 MiB-per-atlas policy. Raising the texture budget alone will not change that choice. Creator Tools' atlas check is a validator signal, not a runtime crash limit.

For the supplied split packs, the full-animation set selects `MINI` (about 146 MiB decoded textures; its 132 MiB item-atlas estimate rounds to 256 MiB). The simple-animation set selects `SEMI` (about 248 MiB decoded textures; its 212 MiB item-atlas estimate also rounds to 256 MiB). These measurements are tracked separately; they are not added together. Creator Tools found no `TEXTUREIMAGE407` atlas failures in the selected variants. The overlay packs lack `pack_icon.png`, which triggers the documented `CPACKICON101` warning.

Creator Tools reports `JSON001` for `Scaffolding_interface/sounds/sound_definitions.json`. The file is valid JSON, and its `sounds` values use string paths as shown in Microsoft's [custom sounds guide](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomsounds?view=minecraft-bedrock-stable). Fettle leaves the file unchanged.

Bedrock 1.26.40 and later can load baked `__brarchive` resources made by the official Bedrock Dedicated Server pack optimizer. Fettle cannot read that archive format. If a pack contains one, Fettle marks its texture, atlas, handle, and file-count estimates incomplete, skips generated variants, and sends the original required pack.

Model cube counts are diagnostics, not crash thresholds. Microsoft's geometry validator reports entity and item counts as information. Its explicit warning above 50 cubes applies to custom block geometry. Fettle does not reduce entity model cubes.

## Configuration and commands

Fettle creates `extensions/fettle/config.properties` on first load. Player overrides go in `extensions/fettle/profiles.properties`.

```properties
profile=auto
match-name-contains=scaffolding,nexo,mythicmobs,modelengine,geysermodelengine
match-pack-uuids=
# Optional budget overrides in MiB:
# texture-budget-unknown-mib=200
# texture-budget-memory-tier-4-mib=1000
```

Use `match-pack-uuids` for exact pack selection. UUIDs are comma-separated and must use the standard dashed format. Name matching remains available for existing configurations.

Admin commands require `fettle.admin`:

- `/fettle device <player|xuid>` shows a player's reported device data, selected profiles, pack sizes, texture estimate, and atlas estimates. It also lists incomplete measurements and the largest texture costs. Use `/fettle audit` for pack-specific warnings. Model counts are separate from texture estimates and live entity counts.
- `/fettle audit` shows cached pack sizes, file and texture counts, atlas estimates, and the largest textures. It reports icon problems, textures over Creator Tools' 4 MiB highest-resolution-mip warning budget, and file-count guidance. Mojang's resource-pack guidance treats 5,000–7,000 files as safer; packs over 10,000 files have caused memory failures on some devices. This differs from the 2,500-file add-on recommendation.
- `/fettle status` shows the configured profile, cached variants, current MemoryTier coverage, profile selections, budget/atlas results, and rejected pack selections grouped by result status. It also shows startup counts for pack-selection attempts, Geyser initialization joins, and disconnects. These counts do not prove that a client rendered the pack. A disconnect before initialization is not proof of a crash. The `disconnectionScreen.resourcePack` reason includes refusals, unknown responses, and some transfer errors. Historical counts are grouped by platform, protocol, graphics mode, tier, and profile, not client version.
- `/fettle optimize <auto|full|semi|mini|safe>` sets the global profile.
- `/fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>` sets a player's profile. `/fettle unset <xuid|online-gamertag>` removes it.
- `/fettle full` sends the original packs. `/fettle reload` reloads the configuration.

In `/fettle device`, `clientDataDecoded=false` means Fettle could not decode Geyser's client-data payload. If it is `true` while `MemoryTier=null`, the payload had no usable tier. Fettle does not infer one from `DeviceOS`. The server log records `clientDataDecoded` and `DeviceOS` for automatic selections without a usable tier.

`MemoryTierClaim` reports `VALID`, `MISSING`, `INVALID`, or `UNDECODABLE`. `/fettle status` groups current sessions with Fettle-managed packs by DeviceOS, or by Geyser's Bedrock platform when DeviceOS is unavailable. These counts are not saved across restarts.

`profile=full` and `/fettle optimize full` select `OPTIMIZE_FULL`. Use `profile=original` or `/fettle full` to send unchanged packs. Changes to `match-name-contains` require a Geyser restart.

## Pack handling

Fettle reads source packs without changing them. It stores generated variants in `extensions/fettle/pack-cache` and prepares them when Geyser loads its pack registry. A cold start with large packs can take several minutes. After Scaffolding regenerates a pack, restart Geyser so Fettle reads the new file. Fettle stores a fingerprint and SHA-256 digest beside each generated pack. If either is missing or does not match, Fettle rebuilds that variant from the source pack. When generated variants exceed 2 GiB, Fettle removes the oldest variants that are not held by the current pack manager. It also removes recognized build temporary files older than 24 hours. Active variants and unrecognized files stay in place, so the cache can remain above 2 GiB when active or unrecognized files exceed that limit.

When both `Scaffolding Animated` and `Scaffolding Animated Simple` are registered, Fettle prepares and audits separate pack sets. In AUTO mode it selects Full if that complete stack fits policy, then Simple if Full does not; if neither fits, the pack event fails. It does not assume the Simple pack costs less. For a fixed profile, the legacy MemoryTier-based alternative choice remains. These static measurements do not predict animation performance or guarantee that a client will avoid a crash. If a selected set was not prepared or its IDs, contents, options or order changed, AUTO fails closed. Install one Scaffolding base release. Split and non-split distributions can reuse a manifest UUID, and duplicate UUIDs prevent Fettle from preparing linked variants. Fettle warns if no profile brings a pack below Mojang's 7,000-file safer guidance.

Generated variants use deterministic header UUIDs that differ by profile, so Bedrock does not reuse the source pack's cached copy for different contents. If a required pack depends on a target pack's UUID, Fettle disables generated variants for that pack set. Changing the UUID could break the dependency.

Fettle uses optimized linked variants only when the session has the same required-pack IDs, contents, options, and effective order as the prepared set. If another extension adds, removes, reorders, or changes a pack, the prepared variants are not reused. AUTO and explicit optimized profiles fail closed when their selected variant is unavailable or the stack cannot be measured. Only an explicit original-pack selection sends the unchanged target packs.

Fettle resizes textures. It does not simplify Bedrock models, reduce MythicMobs or ModelEngine entity counts, or reduce runtime animation work.
