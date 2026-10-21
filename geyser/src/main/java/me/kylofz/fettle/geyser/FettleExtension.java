package me.kylofz.fettle.geyser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;
import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest.Header;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;

public final class FettleExtension implements Extension {
   static final List<String> DEFAULT_MATCH_NAME_TOKENS = List.of("scaffolding", "nexo", "mythicmobs", "modelengine", "geysermodelengine");
   private final DeviceProfileRuntime deviceProfiles = new DeviceProfileRuntime();
   private final Map<String, PackProfile> playerProfiles = new ConcurrentHashMap<>();
   private Path configFile;
   private Path profilesFile;
   private Path packCacheDirectory;
   private PackVariantManager variantManager;
   private volatile PackSetAudit preparedPackSetAudit;
   private volatile PackProfile profile = PackProfile.AUTO;
   private volatile List<String> matchNameTokens = DEFAULT_MATCH_NAME_TOKENS;
   private volatile List<String> activeMatchNameTokens = this.matchNameTokens;
   private volatile Map<Integer, Long> textureBudgetOverrides = Map.of();

   @Subscribe
   public void onPreInitialize(GeyserPreInitializeEvent event) {
      try {
         Path dataFolder = this.dataFolder();
         Files.createDirectories(dataFolder);
         this.preparedPackSetAudit = null;
         this.packCacheDirectory = dataFolder.resolve("pack-cache");
         this.variantManager = new PackVariantManager(this.packCacheDirectory);
         this.configFile = dataFolder.resolve("config.properties");
         this.profilesFile = dataFolder.resolve("profiles.properties");
         this.loadConfig();
         this.loadProfiles();
         this.activeMatchNameTokens = this.matchNameTokens;
      } catch (Throwable throwable) {
         this.logger().severe("Failed to initialize fettle.", throwable);
      }
   }

   @Subscribe
   public void onPostInitialize(GeyserPostInitializeEvent event) {
      this.logger().info("fettle v" + this.description().version() + " loaded with profile " + this.profile.name() + ".");
   }

   @Subscribe
   public void onShutdown(GeyserShutdownEvent event) {
      if (this.variantManager != null) this.variantManager.clear();
      this.preparedPackSetAudit = null;
      this.saveProfiles();
      this.deviceProfiles.clearSessions();
   }

   @Subscribe(postOrder = PostOrder.LAST)
   public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
      this.preparedPackSetAudit = null;
      if (this.variantManager == null) return;
      List<ResourcePack> packs = List.copyOf(event.resourcePacks());
      List<ResourcePack> targets = packs.stream().filter(this::matchesTargetPack).toList();
      for (ResourcePack source : packs) {
         if (!this.matchesTargetPack(source)) {
            try {
               this.variantManager.prepare(source, PackProfile.FULL);
            } catch (Throwable throwable) {
               this.logger().warning("fettle could not audit fixed pack " + source.uuid() + ": "
                  + failureDescription(throwable));
            }
            continue;
         }
      }
      if (!targets.isEmpty()) {
         targets.forEach(source -> this.logger().info("fettle preparing linked device-selectable variants for "
            + source.uuid() + " before accepting Bedrock sessions."));
         long preparationStarted = System.nanoTime();
         try {
            Map<java.util.UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared =
               this.variantManager.prepareSet(targets, packs, PackVariantManager.PREWARM_PROFILES);
            if (!this.variantManager.variantIdentitiesSafe()) {
               this.logger().warning("fettle left optimized variants disabled because required-pack dependencies reference a target pack UUID or could not be verified; original required packs remain selected.");
            }
            for (ResourcePack source : targets) {
               Map<PackProfile, PackVariantManager.PreparedVariant> variants = prepared.get(source.uuid());
               if (variants == null) continue;
               for (PackProfile selected : PackVariantManager.PREWARM_PROFILES) {
                  PackVariantManager.PreparedVariant variant = variants.get(selected);
                  if (variant == null) continue;
                  this.logger().info(
                     "fettle prepared " + selected.name() + " for " + source.uuid() + ": "
                        + this.humanBytes(variant.audit().getTextureMemoryBytes()) + " estimated texture memory"
                        + (variant.audit().getTextureMetricsComplete() ? "" : " (incomplete audit)")
                  );
               }
            }
            this.logger().info("fettle prepared " + targets.size() + " linked pack(s) in "
               + ((System.nanoTime() - preparationStarted) / 1_000_000L) + " ms.");
         } catch (Throwable throwable) {
            this.logger().warning("fettle linked-pack preparation failed: " + failureDescription(throwable)
               + "; required source packs remain available.");
         }
      }
      try {
         PackVariantManager.CacheUsage usage = this.variantManager.cacheUsage();
         if (usage.overWarningThreshold()) {
            this.logger().warning("fettle's pack-cache contains " + this.humanBytes(usage.bytes())
               + ", above the 2 GiB advisory target; cache files were retained.");
         }
      } catch (Exception | LinkageError failure) {
         this.logger().warning("fettle could not inspect pack-cache size: "
            + failure.getClass().getSimpleName());
      }
      this.preparedPackSetAudit = PackSetAudit.forSession(null, packs, this::matchesTargetPack, this.variantManager);
   }

   @Subscribe(postOrder = PostOrder.LAST)
   public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
      GeyserConnection connection = event.connection();
      PackProfile override = this.playerProfiles.get(this.normalizeXuid(connection.xuid()));
      PackSetAudit packSetAudit = PackSetAudit.forSession(
         this.preparedPackSetAudit, event.resourcePacks(), this::matchesTargetPack, this.variantManager);
      if (packSetAudit.targetPacks().isEmpty()) {
         this.deviceProfiles.removeSession(connection);
         return;
      }
      DeviceSelectionPlanner.Plan plan = this.deviceProfiles.plan(
         connection, this.profile, override, this.textureBudgetOverrides,
         packSetAudit);
      BedrockDeviceCapabilities capabilities = plan.capabilities();
      boolean automatic = plan.automatic();
      if (automatic && this.variantManager != null && !packSetAudit.preparedPackStackMatches()) {
         this.logger().warning("fettle skipped optimized profiles for " + connection.bedrockUsername()
            + " because this session's required-pack stack or target set differs from the prewarmed set; original required packs remain selected.");
      }
      DeviceProfilePolicy.Selection selection = plan.selection();
      Map<java.util.UUID, PackProfile> selectedProfiles = new java.util.LinkedHashMap<>();

      for (ResourcePack original : plan.packSetAudit().targetPacks()) {
         PackProfile selected = selection.profiles().getOrDefault(original.uuid(), PackProfile.FULL);
         try {
            ResourcePack replacement = selected == PackProfile.FULL
               ? original
               : this.preparedVariant(plan.packSetAudit(), original, selected);
            if (replacement == null) {
               this.logger().warning("fettle could not load the selected " + selected.name() + " variant for "
                  + original.uuid() + "; keeping the original required pack.");
               selected = PackProfile.FULL;
            } else if (replacement != original && !this.replacePack(event, original, replacement)) {
               this.logger().warning("fettle could not safely replace pack " + original.uuid()
                  + "; the original required pack remains selected.");
               selected = PackProfile.FULL;
            }
         } catch (RuntimeException | LinkageError failure) {
            this.logger().warning("fettle kept original pack " + original.uuid() + " after selection failed: "
               + failure.getClass().getSimpleName());
            selected = PackProfile.FULL;
         }
         selectedProfiles.put(original.uuid(), selected);
      }

      String scaffoldingAnimation = ScaffoldingPackPolicy.selectedAnimation(event.resourcePacks());
      int geyserProtocolVersion = -1;
      try {
         geyserProtocolVersion = connection.protocolVersion();
      } catch (RuntimeException | LinkageError ignored) {
      }
      SessionDeviceSnapshot snapshot = plan.snapshot(selectedProfiles, scaffoldingAnimation, geyserProtocolVersion);
      if (automatic && snapshot.textureHandleMetricsComplete() && snapshot.estimatedTextureHandleUpperBound()
         > PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE) {
         this.logger().warning("fettle estimates up to " + snapshot.estimatedTextureHandleUpperBound() + " referenced texture handles "
            + "across the required pack set for " + connection.bedrockUsername()
            + "; texture resizing does not reduce handle count (Creator Tools' add-on guidance is "
            + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE + ").");
      }
      if (automatic && !snapshot.atlasMetricsComplete()) {
         this.logger().warning("fettle could not completely measure the selected atlas references for "
            + connection.bedrockUsername() + "; AUTO cannot verify Fettle's conservative 256 MiB stack-atlas policy.");
      } else if (automatic && !snapshot.withinStackAtlasPolicy()) {
         this.logger().warning("fettle's selected required-pack set for " + connection.bedrockUsername()
            + " estimates combined item/block atlas allocations of "
            + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedItemAtlasTextureBytes()))
            + "/" + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedBlockAtlasTextureBytes()))
            + "; this exceeds Fettle's conservative 256 MiB per-type stack-atlas policy. Creator Tools validates individual packs; "
            + "this aggregate estimate is not a verified Bedrock runtime limit.");
      }
      if (automatic && !snapshot.budgetKnown()) {
         String tier = plan.selectionMemoryTier() == null
            ? "no usable MemoryTier was reported"
            : "selection MemoryTier " + plan.selectionMemoryTier() + " has no configured texture budget";
         String selected = selectedProfiles.values().stream().distinct().map(PackProfile::name).sorted().toList().toString();
         this.logger().warning("fettle found " + tier + " for " + connection.bedrockUsername()
            + " (clientDataDecoded=" + capabilities.getClientDataDecoded()
            + ", DeviceOS=" + capabilities.getDeviceOs()
            + ", MemoryTierClaim=" + capabilities.getMemoryTierClaimStatus()
            + "); AUTO selected " + selected + " under a conservative "
            + this.humanBytes(snapshot.textureBudgetBytes()) + " selection cap (not a device measurement).");
      } else if (automatic && !snapshot.withinBudget()) {
         this.logger().warning(
            "fettle's selected required-pack set for " + connection.bedrockUsername() + " estimates "
               + this.humanBytes(snapshot.estimatedResourceMemoryBytes()) + " decoded-texture-plus-atlas memory against a "
               + this.humanBytes(snapshot.textureBudgetBytes())
               + (plan.defaultTierBudget() ? " automatic MemoryTier budget" : " configured MemoryTier budget")
               + (snapshot.metricsComplete() ? "." : " (incomplete measurement).")
         );
      }
      this.deviceProfiles.putSession(connection, snapshot);
   }

   // Geyser fires this after pack negotiation and Bedrock world initialization.
   @Subscribe
   public void onSessionJoin(SessionJoinEvent event) {
      GeyserConnection connection = event.connection();
      SessionDeviceSnapshot snapshot = this.deviceProfiles.recordPostInitializationJoin(connection);
      if (snapshot != null) {
         String memoryTier = snapshot.selectionMemoryTier() == null
            ? "with unknown Bedrock MemoryTier"
            : "from Bedrock MemoryTier " + snapshot.selectionMemoryTier();
         this.logger().info("fettle delivered " + snapshot.selectedProfiles().values().stream()
            .distinct().map(PackProfile::name).sorted().toList() + " to " + connection.bedrockUsername()
            + " " + memoryTier + ".");
      }
   }

   @Subscribe(postOrder = PostOrder.FIRST)
   public void onSessionDisconnect(SessionDisconnectEvent event) {
      this.deviceProfiles.recordDisconnectBeforeInitialization(event.connection(), event.disconnectReason());
      this.deviceProfiles.removeSession(event.connection());
   }

   @Subscribe
   public void onDefineCommands(GeyserDefineCommandsEvent event) {
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("status")
            .description("Show fettle profile and live Bedrock capability coverage.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               DeviceProfileRuntime.Summary summary = this.deviceProfiles.summary();
               source.sendMessage("§afettle profile: §f" + this.profile.name());
               source.sendMessage("§7Cached pack audits/variants: §f" + (this.variantManager == null ? 0 : this.variantManager.size()));
               source.sendMessage("§7Fettle-tracked Bedrock sessions: §f" + summary.trackedSessions()
                  + "§7, no usable MemoryTier: §f" + summary.missingMemoryTier()
                  + "§7, client data undecoded: §f" + summary.undecodedClientData());
               source.sendMessage("§7MemoryTier counts: §f" + summary.memoryTiers()
                  + "§7, by platform: §f" + summary.memoryTiersByPlatform());
               source.sendMessage("§7Missing tier by platform/GameVersion/Protocol: §f" + summary.missingMemoryTierByPlatform()
                  + "§7, unknown platform: §f" + summary.missingMemoryTierUnknownPlatform());
               source.sendMessage("§7Unusable MemoryTier claim reasons: §f" + summary.memoryTierFailures());
               source.sendMessage("§7Session profile sets: §f" + summary.profileSets());
               source.sendMessage("§7Selection budget/atlas outcomes by platform/version/protocol/graphics/tier/profile: §f"
                  + summary.selectionOutcomes());
               source.sendMessage("§7Selections awaiting Geyser initialization (not download progress): §f"
                  + summary.pendingInitialization());
               source.sendMessage("§7Pack-selection attempts since startup by platform/protocol/graphics/tier/profile: §f"
                  + summary.packJoinAttemptCounts());
               source.sendMessage("§7Post-initialization joins since startup (not rendering proof): §f"
                  + summary.postInitializationJoinCounts());
               source.sendMessage("§7Average selection-to-initialized time ms (includes download/auth/world init; no version grouping): §f"
                  + summary.postInitializationAverageMillis());
               source.sendMessage("§7Pre-initialization disconnects since startup (not crash proof): §f"
                  + summary.preInitializationDisconnectCounts());
               source.sendMessage("§7Geyser resource-pack-reason disconnects (version not retained; not crash proof): §f"
                  + summary.resourcePackReasonDisconnectCounts());
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("device")
            .description("Inspect the reported Bedrock capabilities used for pack selection.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUse /fettle device <online-gamertag|xuid>");
                  return;
               }
               String xuid = this.resolveXuid(args[0]);
               GeyserConnection connection = xuid.isEmpty() ? null : this.geyserApi().connectionByXuid(xuid);
               SessionDeviceSnapshot snapshot = connection == null ? null : this.deviceProfiles.session(connection);
               BedrockDeviceCapabilities capabilities = snapshot == null ? null : snapshot.capabilities();
               if (capabilities == null) {
                  source.sendMessage("§eNo Bedrock capability snapshot is available for that online player.");
                  return;
               }
               String model = capabilities.getDeviceModel() == null
                  ? "unknown"
                  : capabilities.getDeviceModel().replace("§", "");
               String textureBudget = snapshot.budgetKnown()
                  ? this.humanBytes(snapshot.textureBudgetBytes())
                  : snapshot.textureBudgetBytes() > 0
                     ? "unknown (conservative AUTO cap " + this.humanBytes(snapshot.textureBudgetBytes()) + ")"
                  : capabilities.getClientDataDecoded()
                     ? "unknown (decoded client data has no usable MemoryTier)"
                     : "unknown (client data unavailable or unparseable)";
               String animationVariant = snapshot.scaffoldingAnimationVariant() == null
                  ? "not present"
                  : snapshot.scaffoldingAnimationVariant();
               long packFileCount = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null)
                  .mapToLong(pack -> pack.report().getFileCount())
                  .sum();
               boolean packFileCountComplete = snapshot.packAudits().stream().allMatch(pack -> pack.report() != null);
               source.sendMessage(
                  "§afettle device claims for §f"
                     + xuid
                     + "§a: platform=§f"
                     + String.valueOf(capabilities.getPlatform())
                     + "§a, clientDataDecoded=§f"
                     + capabilities.getClientDataDecoded()
                     + "§a, DeviceOS=§f"
                     + String.valueOf(capabilities.getDeviceOs())
                     + (capabilities.getDeviceOsLabel() == null ? "" : " (" + capabilities.getDeviceOsLabel() + ")")
                     + "§a, model=§f"
                     + model
                     + "§a, gameVersion=§f"
                     + String.valueOf(capabilities.getGameVersion())
                     + "§a, protocolVersion=§f"
                     + snapshot.geyserProtocolVersion()
                     + "§a, MemoryTier=§f"
                     + String.valueOf(capabilities.getMemoryTier())
                     + " ("
                     + capabilities.getMemoryTierDescription()
                     + ")"
                     + "§a, MemoryTierClaim=§f"
                     + capabilities.getMemoryTierClaimStatus()
               );
               source.sendMessage(
                  "§7viewDistance="
                     + String.valueOf(capabilities.getMaxViewDistance())
                     + ", graphicsMode="
                     + String.valueOf(capabilities.getGraphicsMode())
                     + ", chunkGen="
                     + String.valueOf(capabilities.getCompatibleWithClientSideChunkGen())
                     + ", selectionMemoryTier="
                     + String.valueOf(snapshot.selectionMemoryTier())
                     + ", scaffoldingAnimation="
                     + animationVariant
                     + ", packArchiveBytes~="
                     + snapshot.packAudits().stream()
                        .filter(pack -> pack.report() != null)
                        .map(pack -> pack.name().replace("§", "") + " (" + pack.profile() + ")="
                           + this.humanBytes(pack.report().getArchiveBytes()))
                        .toList()
                     + ", packFiles~="
                     + packFileCount
                     + (packFileCountComplete ? "" : " (partial)")
                     + ", textureEstimate="
                     + this.humanBytes(snapshot.estimatedTextureBytes())
                     + ", texturePlusAtlasEstimate="
                     + this.humanBytes(snapshot.estimatedResourceMemoryBytes())
                     + ", textureFiles~="
                     + snapshot.estimatedTextureFileCount()
                     + (snapshot.metricsComplete() ? "" : " (partial)")
                     + ", stackItemAtlasEstimate~="
                     + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedItemAtlasTextureBytes()))
                     + ", stackBlockAtlasEstimate~="
                     + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedBlockAtlasTextureBytes()))
                     + ", atlasMetrics="
                     + (snapshot.atlasMetricsComplete() ? "complete" : "partial")
                     + ", stackAtlasPolicyFit="
                     + (snapshot.atlasMetricsComplete() ? Boolean.toString(snapshot.withinStackAtlasPolicy()) : "unknown")
                     + ", textureHandles~="
                     + snapshot.estimatedTextureHandleUpperBound()
                     + (snapshot.textureHandleMetricsComplete() ? "" : " (partial)")
                     + ", handleGuidance="
                     + (!snapshot.textureHandleMetricsComplete() ? "unknown"
                        : snapshot.estimatedTextureHandleUpperBound() <= PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE
                           ? "within-" + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE
                           : "upper-bound-over-" + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE)
                     + ", textureBudget="
                     + textureBudget
                     + ", budgetSource="
                     + (snapshot.defaultTierBudget() ? "automatic-MemoryTier-budget"
                        : snapshot.budgetKnown() ? "configured-MemoryTier-budget"
                           : snapshot.textureBudgetBytes() > 0 ? "conservative-unknown-tier-cap" : "no-usable-MemoryTier")
                     + ", budgetFit="
                     + (snapshot.budgetKnown() && snapshot.metricsComplete() ? Boolean.toString(snapshot.withinBudget()) : "unknown")
                     + ", selectedProfiles="
                     + snapshot.selectedProfiles()
               );
               List<PackSetAudit.PackAuditSnapshot> largestModels = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getLargestModelPath() != null)
                  .sorted(java.util.Comparator.comparingInt(
                     (PackSetAudit.PackAuditSnapshot pack) -> pack.report().getMaxModelCubes()).reversed())
                  .limit(3)
                  .toList();
               if (!largestModels.isEmpty()) {
                  source.sendMessage("§7Static model scan (separate from texture budget and live entities):");
                  largestModels.forEach(pack -> {
                     PackAuditReport report = pack.report();
                     source.sendMessage("§7" + pack.name().replace("§", "") + " (" + pack.profile() + "): "
                        + report.getModelFileCount() + " model files; "
                        + report.getLargestModelPath().replace("§", "") + " ("
                        + report.getMaxModelCubes() + " cubes).");
                  });
               }
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null)
                  .forEach(pack -> pack.report().getWarnings().stream()
                     .filter(warning -> warning.contains("block model file") && warning.contains("50 cubes"))
                     .forEach(warning -> source.sendMessage("§eBlock geometry "
                        + pack.name().replace("§", "") + " (" + pack.profile() + "): " + warning)));
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getRootFontGlyphCount() > 0)
                  .forEach(pack -> source.sendMessage("§7Root font pages in " + pack.name().replace("§", "")
                     + " (" + pack.profile() + "): " + pack.report().getRootFontGlyphCount() + " page(s), "
                     + this.humanBytes(pack.report().getRootFontGlyphTextureMemoryBytes())
                     + " measured; outside the documented path and counted conservatively."));
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getOversizedModelCount() > 0)
                  .forEach(pack -> source.sendMessage("§eStatic model scan incomplete for "
                     + pack.name().replace("§", "") + ": " + pack.report().getOversizedModelCount()
                     + " model file(s) exceeded the scan bound."));
               List<PackSetAudit.PackAuditSnapshot> incompletePacks = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() == null || !pack.report().getTextureMetricsComplete())
                  .toList();
               if (!incompletePacks.isEmpty()) {
                  incompletePacks.forEach(pack -> {
                     PackAuditReport report = pack.report();
                     source.sendMessage("§ePack measurement: §f" + pack.name() + " §7(" + pack.id() + ", "
                        + pack.profile() + ") §e" + (report == null ? "audit unavailable."
                           : report.getUnmeasuredTextureCount() + " texture(s) unmeasured; known estimate "
                              + this.humanBytes(report.getTextureMemoryBytes()) + "."));
                     if (report != null) {
                        report.getWarnings().stream()
                           .filter(warning -> warning.contains("textures could not be measured"))
                           .forEach(warning -> source.sendMessage("§7" + warning));
                     }
                  });
               } else if (!snapshot.budgetKnown() || !snapshot.withinBudget()) {
                  snapshot.packAudits().stream()
                     .filter(pack -> pack.report() != null)
                     .sorted(java.util.Comparator.comparingLong(
                        (PackSetAudit.PackAuditSnapshot pack) -> pack.report().getTextureMemoryBytes()).reversed())
                     .limit(3)
                     .forEach(pack -> source.sendMessage("§7Largest pack cost: §f" + pack.name() + " §7("
                        + pack.profile() + ", " + pack.id() + "): §f"
                        + this.humanBytes(pack.report().getTextureMemoryBytes()) + "."));
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("optimize")
            .description("Set fettle's pack optimization profile.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUse /fettle optimize <auto|full|semi|mini|safe>");
               } else {
                  PackProfile requested = this.parseProfile(args[0]);
                  if (requested != null && requested != PackProfile.FULL) {
                     this.profile = requested;
                     if (!this.saveConfig()) {
                        source.sendMessage("§cProfile changed in memory, but config.properties could not be saved; restart will restore the saved profile.");
                        return;
                     }
                     source.sendMessage("§afettle profile set to §f" + this.profile.name() + "§a. Reconnect to apply it.");
                  } else {
                     source.sendMessage("§cProfile must be auto, full, semi, mini, or safe.");
                  }
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("audit")
            .description("Audit the cached fettle pack's low-end costs.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor(
               (source, command, args) -> {
                  List<PackVariantManager.PreparedVariant> audits = this.variantManager == null
                     ? List.of()
                     : this.variantManager.cachedVariants();
                  if (audits.isEmpty()) {
                     source.sendMessage("§eNo pack audits or variants have been prepared.");
                  } else {
                     audits
                        .stream()
                        .sorted(java.util.Comparator.comparing((PackVariantManager.PreparedVariant pack) -> pack.pack().uuid().toString())
                           .thenComparing(pack -> pack.profile().name()))
                        .forEach(cached -> {
                           PackAuditReport report = cached.audit();
                           String kind = this.matchesTargetPack(cached.pack()) ? cached.profile().name() : "FIXED";
                           source.sendMessage(
                              "§afettle " + kind + " §7(" + cached.pack().uuid() + "): §f"
                                 + report.getFileCount() + " files, " + report.getTextureFileCount() + " textures, "
                                 + report.getEstimatedTextureHandleCount() + " estimated handles, "
                                 + this.humanBytes(report.getArchiveBytes()) + " archive, "
                                 + String.format(Locale.ROOT, "%.1f MiB", report.getTextureMemoryMiB())
                                 + " texture memory; " + report.getModelFileCount() + " models, max "
                                 + report.getMaxModelCubes() + " cubes; metrics complete="
                                 + report.getTextureMetricsComplete() + "."
                           );
                           if (report.getLargestTexturePath() != null) {
                              source.sendMessage(
                                 "§7Largest texture: §f" + report.getLargestTexturePath().replace("§", "") + " §7("
                                    + report.getLargestTextureWidth() + "x" + report.getLargestTextureHeight() + ", "
                                    + this.humanBytes(report.getLargestTextureMemoryBytes()) + ")."
                              );
                           }
                           report.getTextureHotspots().stream().skip(1).limit(2).forEach(hotspot ->
                              source.sendMessage("§7Next texture: §f" + hotspot.getPath().replace("§", "") + " §7("
                                 + hotspot.getWidth() + "x" + hotspot.getHeight() + ", "
                                 + this.humanBytes(hotspot.getMemoryBytes()) + ").")
                           );
                           if (report.getLargestPotentialAtlasTexturePath() != null) {
                              source.sendMessage(
                                 "§7Largest atlas candidate: §f" + report.getLargestPotentialAtlasTexturePath().replace("§", "")
                                    + " §7(" + this.humanBytes(report.getLargestPotentialAtlasTextureMemoryBytes()) + ")."
                              );
                           }
                           if (report.getLargestModelPath() != null) {
                              source.sendMessage(
                                 "§7Largest model: §f" + report.getLargestModelPath().replace("§", "") + " §7("
                                    + report.getMaxModelCubes() + " cubes)."
                              );
                           }
                           if (report.getRootFontGlyphCount() > 0) {
                              source.sendMessage("§7Root font glyph pages: " + report.getRootFontGlyphCount()
                                 + ", measured memory " + this.humanBytes(report.getRootFontGlyphTextureMemoryBytes())
                                 + "; outside the documented path, counted conservatively.");
                           }
                           report.getWarnings().forEach(warning -> source.sendMessage("§e- " + warning));
                        });
                  }
               }
            )
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("set")
            .description("Set one Bedrock player's fettle profile.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 2) {
                  source.sendMessage("§cUse /fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>");
               } else {
                  String xuid = this.resolveXuid(args[0]);
                  PackProfile requested = this.parseOverrideProfile(args[1]);
                  if (xuid.isEmpty()) {
                     source.sendMessage("§cThat player is not online and is not a numeric XUID.");
                  } else if (requested == null) {
                     source.sendMessage("§cProfile must be auto, original, optimize-full, optimize-semi, optimize-mini, or safe.");
                  } else {
                     this.playerProfiles.put(xuid, requested);
                     if (!this.saveProfiles()) {
                        source.sendMessage("§cProfile set in memory, but profiles.properties could not be saved; restart will restore the saved value.");
                        return;
                     }
                     source.sendMessage("§aSet §f" + xuid + " §ato §f" + requested.name() + "§a. Reconnect that Bedrock player to apply it.");
                     GeyserConnection online = this.geyserApi().connectionByXuid(xuid);
                     if (online != null) {
                        online.sendMessage("§eYour fettle graphics profile is now §f" + requested.name() + "§e. Reconnect to apply it.");
                     }
                  }
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("unset")
            .description("Remove one Bedrock player's fettle override.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUse /fettle unset <xuid|online-gamertag>");
               } else {
                  String xuid = this.resolveXuid(args[0]);
                  if (xuid.isEmpty()) {
                     source.sendMessage("§cThat player is not online and is not a numeric XUID.");
                  } else {
                     this.playerProfiles.remove(xuid);
                     if (!this.saveProfiles()) {
                        source.sendMessage("§cOverride removed in memory, but profiles.properties could not be saved; it may return after restart.");
                        return;
                     }
                     source.sendMessage("§aRemoved the fettle override for §f" + xuid + "§a.");
                  }
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("full")
            .description("Send the original resource pack unchanged.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               this.profile = PackProfile.FULL;
               if (!this.saveConfig()) {
                  source.sendMessage("§cOriginal-pack mode is active in memory, but config.properties could not be saved; restart will restore the saved profile.");
                  return;
               }
               source.sendMessage("§afettle now sends original packs unchanged.");
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("reload")
            .description("Reload fettle configuration.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               List<String> activeMatches = this.activeMatchNameTokens;
               boolean configLoaded = this.loadConfig();
               boolean profilesLoaded = this.loadProfiles();
               if (!configLoaded || !profilesLoaded) {
                  List<String> failedFiles = new ArrayList<>();
                  if (!configLoaded) failedFiles.add("config.properties");
                  if (!profilesLoaded) failedFiles.add("profiles.properties");
                  source.sendMessage("§cCould not reload " + String.join(" and ", failedFiles)
                     + "; existing in-memory values were retained for those files.");
                  return;
               }
               source.sendMessage(activeMatches.equals(this.matchNameTokens)
                  ? "§afettle profiles and budgets reloaded."
                  : "§afettle profiles and budgets reloaded; match-name changes require a Geyser restart to prepare variants.");
            })
            .build()
      );
   }

   private ResourcePack preparedVariant(PackSetAudit packSetAudit, ResourcePack original, PackProfile selectedProfile) {
      PackVariantManager.PreparedVariant variant =
         packSetAudit.preparedVariant(original, selectedProfile, this.variantManager);
      return variant == null ? null : variant.pack();
   }

   private PackProfile parseOverrideProfile(String value) {
      if (value == null) {
         return null;
      }

      return switch (value.trim().toUpperCase(Locale.ROOT)) {
         case "AUTO" -> PackProfile.AUTO;
         case "FULL", "ORIGINAL", "RAW" -> PackProfile.FULL;
         case "OPTIMIZE_FULL", "OPT_FULL" -> PackProfile.OPTIMIZE_FULL;
         case "OPTIMIZE_SEMI", "OPT_SEMI", "SEMI", "BALANCED" -> PackProfile.OPTIMIZE_SEMI;
         case "OPTIMIZE_MINI", "OPT_MINI", "MINI", "LOW", "LITE" -> PackProfile.OPTIMIZE_MINI;
         case "SAFE" -> PackProfile.SAFE;
         default -> null;
      };
   }

   private String resolveXuid(String token) {
      String normalized = this.normalizeXuid(token);
      if (!normalized.isEmpty()) {
         return normalized;
      }

      for (GeyserConnection connection : this.geyserApi().onlineConnections()) {
         if (connection.bedrockUsername().equalsIgnoreCase(token)) {
            return this.normalizeXuid(connection.xuid());
         }
      }

      return "";
   }

   private String normalizeXuid(String value) {
      if (value != null && !value.isBlank()) {
         for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
               return "";
            }
         }

         return value;
      } else {
         return "";
      }
   }

   private boolean replacePack(SessionLoadResourcePacksEvent event, ResourcePack original, ResourcePack replacement) {
      Collection<ResourcePackOption<?>> options;
      try {
         options = new ArrayList<>(event.options(original.uuid()));
      } catch (RuntimeException | LinkageError ignored) {
         return false;
      }

      ResourcePackOption<?>[] reusable = options.stream().filter(Objects::nonNull).toArray(ResourcePackOption[]::new);
      if (!event.unregister(original.uuid())) return false;

      try {
         event.register(replacement, reusable);
         return true;
      } catch (RuntimeException | LinkageError replacementFailure) {
         try {
            event.unregister(original.uuid());
            event.register(original, reusable);
            return false;
         } catch (RuntimeException | LinkageError restoreFailure) {
            restoreFailure.addSuppressed(replacementFailure);
            try {
               event.register(original, new ResourcePackOption<?>[0]);
               this.logger().warning("fettle restored pack " + original.uuid() + " without original pack options after variant registration failed.");
               return false;
            } catch (RuntimeException | LinkageError finalRestoreFailure) {
               finalRestoreFailure.addSuppressed(restoreFailure);
               throw finalRestoreFailure;
            }
         }
      }
   }

   static String failureDescription(Throwable failure) {
      String message = failure.getMessage();
      if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
      String safe = message.replaceAll("[\\p{Cntrl}§]", " ").trim();
      return failure.getClass().getSimpleName() + ": "
         + (safe.length() > 200 ? safe.substring(0, 200) + "…" : safe);
   }

   private boolean matchesTargetPack(ResourcePack pack) {
      String text = this.describe(pack).toLowerCase(Locale.ROOT);
      return this.activeMatchNameTokens.stream().anyMatch(token -> !token.isBlank() && text.contains(token));
   }

   private String describe(ResourcePack pack) {
      try {
         Header header = pack.manifest().header();
         return header.name() + " " + header.description();
      } catch (Throwable ignored) {
         return pack.uuid().toString();
      }
   }

   PackProfile parseProfile(String value) {
      if (value == null) {
         return null;
      }

      return switch (value.trim().toUpperCase(Locale.ROOT)) {
         case "AUTO" -> PackProfile.AUTO;
         case "ORIGINAL", "RAW" -> PackProfile.FULL;
         case "FULL", "OPTIMIZE_FULL", "OPT_FULL" -> PackProfile.OPTIMIZE_FULL;
         case "SEMI", "OPTIMIZE_SEMI", "OPT_SEMI", "BALANCED" -> PackProfile.OPTIMIZE_SEMI;
         case "MINI", "OPTIMIZE_MINI", "OPT_MINI" -> PackProfile.OPTIMIZE_MINI;
         case "SAFE" -> PackProfile.SAFE;
         default -> null;
      };
   }

   static String profileConfigValue(PackProfile profile) {
      return switch (profile) {
         case AUTO -> "auto";
         case FULL -> "original";
         case OPTIMIZE_FULL -> "full";
         case OPTIMIZE_SEMI -> "semi";
         case OPTIMIZE_MINI -> "mini";
         case SAFE -> "safe";
      };
   }

   private synchronized boolean loadConfig() {
      if (this.configFile == null) return false;
      try {
         if (!Files.isRegularFile(this.configFile)) {
            return this.saveConfig();
         }

         Properties properties = new Properties();

         try (InputStream input = Files.newInputStream(this.configFile)) {
            properties.load(input);
         }

         PackProfile configured = this.parseProfile(properties.getProperty("profile", "auto"));
         PackProfile loadedProfile = configured == null ? PackProfile.AUTO : configured;
         List<String> loadedMatchNameTokens = parseMatchNameTokens(properties.getProperty(
            "match-name-contains", "scaffolding,nexo,mythicmobs,modelengine,geysermodelengine"
         ));
         Map<Integer, Long> budgets = new HashMap<>();
         for (int tier = 0; tier <= 4; tier++) {
            String key = "texture-budget-memory-tier-" + tier + "-mib";
            String value = properties.getProperty(key);
            if (value == null) continue;
            Long bytes = DeviceProfilePolicy.parseTextureBudgetMib(value);
            if (bytes == null) {
               this.logger().warning("fettle ignored invalid " + key + " value; expected 0–8192 MiB.");
            } else {
               budgets.put(tier, bytes);
            }
         }
         this.profile = loadedProfile;
         this.matchNameTokens = loadedMatchNameTokens;
         this.textureBudgetOverrides = Map.copyOf(budgets);
         return true;
      } catch (Throwable throwable) {
         this.logger().severe("Failed to load fettle config.properties.", throwable);
         return false;
      }
   }

   private synchronized boolean saveConfig() {
      if (this.configFile == null) return false;
      try {
         Properties properties = new Properties();
         if (Files.isRegularFile(this.configFile)) {
            try (InputStream input = Files.newInputStream(this.configFile)) {
               properties.load(input);
            }
         }
         for (String obsolete : List.of(
            "debug-initial-connection", "auto-rescue-enabled", "auto-rescue-failures",
            "auto-rescue-window-seconds", "failed-join-max-seconds", "safe-filter-model-engine",
            "safe-filter-geyser-model-engine"
         )) {
            properties.remove(obsolete);
         }
         properties.setProperty("profile", profileConfigValue(this.profile));
         properties.setProperty("match-name-contains", String.join(",", this.matchNameTokens));
         for (int tier = 0; tier <= 4; tier++) {
            String key = "texture-budget-memory-tier-" + tier + "-mib";
            Long bytes = this.textureBudgetOverrides.get(tier);
            if (bytes != null) properties.setProperty(key, Long.toString(bytes / (1024L * 1024L)));
            else properties.remove(key);
         }
         Path temp = this.configFile.resolveSibling(this.configFile.getFileName() + ".tmp");

         try (OutputStream output = Files.newOutputStream(temp)) {
            properties.store(output, "fettle pack optimizer");
         }

         try {
            Files.move(temp, this.configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, this.configFile, StandardCopyOption.REPLACE_EXISTING);
         }
         return true;
      } catch (IOException exception) {
         this.logger().warning("Failed to save fettle config.properties: " + exception.getMessage());
         return false;
      }
   }

   private synchronized boolean loadProfiles() {
      if (this.profilesFile == null) return false;
      Properties properties = new Properties();
      try (InputStream input = Files.newInputStream(this.profilesFile)) {
         properties.load(input);
      } catch (java.nio.file.NoSuchFileException missing) {
         // A missing profiles file means no per-player overrides.
      } catch (IOException | IllegalArgumentException exception) {
         this.logger().warning("Failed to load fettle profiles.properties; keeping current profiles: " + exception.getMessage());
         return false;
      }

      Map<String, PackProfile> loadedProfiles = new HashMap<>();
      for (String key : properties.stringPropertyNames()) {
         String xuid = this.normalizeXuid(key);
         PackProfile requested = this.parseOverrideProfile(properties.getProperty(key));
         if (!xuid.isEmpty() && requested != null) loadedProfiles.put(xuid, requested);
      }
      this.playerProfiles.clear();
      this.playerProfiles.putAll(loadedProfiles);
      return true;
   }

   private synchronized boolean saveProfiles() {
      if (this.profilesFile == null) return false;
      try {
         Properties properties = new Properties();
         if (Files.isRegularFile(this.profilesFile)) {
            try (InputStream input = Files.newInputStream(this.profilesFile)) {
               properties.load(input);
            }
         }
         for (String key : properties.stringPropertyNames()) {
            if (!this.normalizeXuid(key).isEmpty()) properties.remove(key);
         }
         this.playerProfiles.forEach((xuid, requested) -> properties.setProperty(xuid, requested.name()));
         Path temp = this.profilesFile.resolveSibling(this.profilesFile.getFileName() + ".tmp");

         try (OutputStream output = Files.newOutputStream(temp)) {
            properties.store(output, "fettle per-player profile overrides");
         }

         try {
            Files.move(temp, this.profilesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, this.profilesFile, StandardCopyOption.REPLACE_EXISTING);
         }
         return true;
      } catch (IOException exception) {
         this.logger().warning("Failed to save fettle profiles.properties: " + exception.getMessage());
         return false;
      }
   }

   static List<String> parseMatchNameTokens(String value) {
      List<String> result = new ArrayList<>();

      for (String token : value.split(",")) {
         String normalized = token.trim().toLowerCase(Locale.ROOT);
         if (!normalized.isBlank()) {
            result.add(normalized);
         }
      }

      return List.copyOf(result);
   }

   private String humanBytes(long bytes) {
      if (bytes < 1024L) {
         return bytes + " B";
      }

      double kb = bytes / 1024.0;
      return kb < 1024.0 ? String.format(Locale.ROOT, "%.1f KB", kb) : String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
   }

}
