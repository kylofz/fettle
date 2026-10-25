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
import java.util.EnumMap;
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
   private volatile Map<ScaffoldingPackPolicy.AnimationVariant, PackSetAudit> preparedPackSetAudits = Map.of();
   private volatile PackProfile profile = PackProfile.AUTO;
   private volatile List<String> matchNameTokens = DEFAULT_MATCH_NAME_TOKENS;
   private volatile List<String> activeMatchNameTokens = this.matchNameTokens;
   private volatile Map<Integer, Long> textureBudgetOverrides = Map.of();

   @Subscribe
   public void onPreInitialize(GeyserPreInitializeEvent event) {
      try {
         Path dataFolder = this.dataFolder();
         Files.createDirectories(dataFolder);
         this.preparedPackSetAudits = Map.of();
         this.packCacheDirectory = dataFolder.resolve("pack-cache");
         this.variantManager = new PackVariantManager(this.packCacheDirectory);
         this.configFile = dataFolder.resolve("config.properties");
         this.profilesFile = dataFolder.resolve("profiles.properties");
         this.loadConfig();
         this.loadProfiles();
         this.activeMatchNameTokens = this.matchNameTokens;
      } catch (Throwable throwable) {
         this.logger().severe("Fettle could not initialize.", throwable);
      }
   }

   @Subscribe
   public void onPostInitialize(GeyserPostInitializeEvent event) {
      this.logger().info("Fettle v" + this.description().version() + " loaded. Profile: " + this.profile.name() + ".");
   }

   @Subscribe
   public void onShutdown(GeyserShutdownEvent event) {
      if (this.variantManager != null) this.variantManager.clear();
      this.preparedPackSetAudits = Map.of();
      this.saveProfiles();
      this.deviceProfiles.clearSessions();
   }

   @Subscribe(postOrder = PostOrder.LAST)
   public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
      this.preparedPackSetAudits = Map.of();
      if (this.variantManager == null) return;
      List<ResourcePack> packs = List.copyOf(event.resourcePacks());
      ResourcePack fullAnimation = ScaffoldingPackPolicy.find(packs, ScaffoldingPackPolicy.ANIMATED_PACK);
      ResourcePack simpleAnimation = ScaffoldingPackPolicy.find(packs, ScaffoldingPackPolicy.SIMPLE_PACK);
      boolean perClientAnimationChoice = fullAnimation != null && simpleAnimation != null
         && this.matchesTargetPack(fullAnimation) && this.matchesTargetPack(simpleAnimation);
      Map<ScaffoldingPackPolicy.AnimationVariant, List<ResourcePack>> candidateStacks =
         new EnumMap<>(ScaffoldingPackPolicy.AnimationVariant.class);
      if (perClientAnimationChoice) {
         candidateStacks.put(ScaffoldingPackPolicy.AnimationVariant.FULL,
            ScaffoldingPackPolicy.stackWithout(packs, simpleAnimation));
         candidateStacks.put(ScaffoldingPackPolicy.AnimationVariant.SIMPLE,
            ScaffoldingPackPolicy.stackWithout(packs, fullAnimation));
         this.logger().info("Scaffolding packs found. Fettle will use Simple for reported MemoryTier 0–3 or missing, and Full for tier 4+.");
      } else {
         candidateStacks.put(ScaffoldingPackPolicy.registeredVariant(packs), packs);
      }

      for (ResourcePack source : packs) {
         if (!this.matchesTargetPack(source)) {
            try {
               this.variantManager.prepare(source, PackProfile.FULL);
            } catch (Throwable throwable) {
               this.logger().warning("Could not audit fixed pack " + source.uuid() + ": "
                  + failureDescription(throwable));
            }
            continue;
         }
      }

      Map<ScaffoldingPackPolicy.AnimationVariant, PackSetAudit> prepared =
         new EnumMap<>(ScaffoldingPackPolicy.AnimationVariant.class);
      candidateStacks.forEach((animation, packStack) -> {
         try {
            prepared.put(animation, this.preparePackSet(packStack, animation));
         } catch (Throwable throwable) {
            this.logger().warning("Could not prepare the " + animation.name() + " Scaffolding pack set: "
               + failureDescription(throwable) + ". Original required packs remain available.");
         }
      });
      this.preparedPackSetAudits = Map.copyOf(prepared);
      try {
         PackVariantManager.CacheCleanup cleanup = this.variantManager.pruneGeneratedArtifacts();
         if (cleanup.deletedFiles() > 0) {
            this.logger().info("Removed " + cleanup.deletedFiles() + " inactive generated pack-cache files ("
               + this.humanBytes(cleanup.deletedBytes()) + "); active variants and unrecognized files were kept.");
         }
         if (cleanup.failedDeletes() > 0) {
            this.logger().warning("Could not remove " + cleanup.failedDeletes()
               + " inactive generated pack-cache files.");
         }
         PackVariantManager.CacheUsage usage = this.variantManager.cacheUsage();
         if (usage.overWarningThreshold()) {
            this.logger().warning("Fettle's pack cache uses " + this.humanBytes(usage.bytes())
               + ", above the 2 GiB advisory limit after safe cleanup of inactive generated variants.");
         }
      } catch (Exception | LinkageError failure) {
         this.logger().warning("Could not prune or measure pack-cache size: "
            + failure.getClass().getSimpleName());
      }
   }

   private PackSetAudit preparePackSet(
      List<ResourcePack> packs,
      ScaffoldingPackPolicy.AnimationVariant animation) throws Exception {
      List<ResourcePack> targets = packs.stream().filter(this::matchesTargetPack).toList();
      if (!targets.isEmpty()) {
         targets.forEach(source -> this.logger().info("Preparing " + animation.name()
            + " Scaffolding variants for pack " + source.uuid() + " before Bedrock sessions connect."));
         long preparationStarted = System.nanoTime();
         Map<java.util.UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared =
            this.variantManager.prepareSet(targets, packs, PackVariantManager.PREWARM_PROFILES);
         if (!this.variantManager.variantIdentitiesSafe()) {
            this.logger().warning("Disabled optimized variants for the " + animation.name()
               + " Scaffolding pack set because a required-pack dependency uses a target pack UUID, or Fettle could not verify it. Original packs will be sent.");
         }
         for (ResourcePack source : targets) {
            Map<PackProfile, PackVariantManager.PreparedVariant> variants = prepared.get(source.uuid());
            if (variants == null) continue;
            for (PackProfile selected : PackVariantManager.PREWARM_PROFILES) {
               PackVariantManager.PreparedVariant variant = variants.get(selected);
               if (variant == null) continue;
               this.logger().info(
                  "Prepared " + selected.name() + " for pack " + source.uuid() + ": estimated texture memory "
                     + this.humanBytes(variant.audit().getTextureMemoryBytes())
                     + (variant.audit().getTextureMetricsComplete() ? "." : " (incomplete measurement).")
               );
            }
            int minimumFileCount = variants.values().stream()
               .mapToInt(variant -> variant.audit().getFileCount())
               .min().orElse(Integer.MAX_VALUE);
            if (minimumFileCount > PackAuditReport.MOJANG_SAFER_PACK_FILE_COUNT) {
               this.logger().warning("No prepared profile brought pack " + source.uuid() + " below Mojang's safer "
                  + PackAuditReport.MOJANG_SAFER_PACK_FILE_COUNT + "-file guidance. The smallest measured version has "
                  + minimumFileCount + " files. Resizing textures does not reduce model or animation work.");
            }
         }
         this.logger().info("Prepared " + targets.size() + " linked " + animation.name() + " Scaffolding packs in "
            + ((System.nanoTime() - preparationStarted) / 1_000_000L) + " ms.");
      }
      return PackSetAudit.collect(packs, targets, this::matchesTargetPack, this.variantManager);
   }

   @Subscribe(postOrder = PostOrder.LAST)
   public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
      GeyserConnection connection = event.connection();
      PackProfile override = this.playerProfiles.get(this.normalizeXuid(connection.xuid()));
      BedrockDeviceCapabilities capabilities = this.deviceProfiles.readCapabilities(connection);
      ResourcePack fullAnimation = ScaffoldingPackPolicy.find(event.resourcePacks(), ScaffoldingPackPolicy.ANIMATED_PACK);
      ResourcePack simpleAnimation = ScaffoldingPackPolicy.find(event.resourcePacks(), ScaffoldingPackPolicy.SIMPLE_PACK);
      ScaffoldingPackPolicy.Selection animationChoice = fullAnimation != null && simpleAnimation != null
         && this.matchesTargetPack(fullAnimation) && this.matchesTargetPack(simpleAnimation)
         ? ScaffoldingPackPolicy.selectForMemoryTier(event.resourcePacks(), capabilities.getMemoryTier())
         : ScaffoldingPackPolicy.currentSelection(event.resourcePacks());
      PackSetAudit prewarmedPackSetAudit = this.preparedPackSetAudits.get(animationChoice.variant());
      if (animationChoice.omitted() != null) {
         List<ResourcePack> selectedStack = ScaffoldingPackPolicy.stackWithout(
            event.resourcePacks(), animationChoice.omitted());
         if (prewarmedPackSetAudit == null || !prewarmedPackSetAudit.matches(selectedStack)) {
            this.logger().warning("Kept both Scaffolding animation packs for " + connection.bedrockUsername()
               + ". The selected " + animationChoice.variant().name()
               + " pack set was not prepared or its IDs/content changed. Original packs will be sent.");
            animationChoice = ScaffoldingPackPolicy.currentSelection(event.resourcePacks());
            prewarmedPackSetAudit = this.preparedPackSetAudits.get(animationChoice.variant());
         } else if (!event.unregister(animationChoice.omitted().uuid())) {
            this.logger().warning("Could not remove one Scaffolding animation pack for "
               + connection.bedrockUsername() + ". Original packs will be sent.");
            animationChoice = ScaffoldingPackPolicy.currentSelection(event.resourcePacks());
            prewarmedPackSetAudit = this.preparedPackSetAudits.get(animationChoice.variant());
         } else {
            this.logger().info("Selected " + animationChoice.variant().name() + " Scaffolding animation for "
               + connection.bedrockUsername() + " from reported MemoryTier "
               + (capabilities.getMemoryTier() == null ? "unknown" : capabilities.getMemoryTier()) + ".");
         }
      }
      PackSetAudit packSetAudit = PackSetAudit.forSession(
         prewarmedPackSetAudit, event.resourcePacks(), this::matchesTargetPack, this.variantManager);
      if (packSetAudit.targetPacks().isEmpty()) {
         this.deviceProfiles.removeSession(connection);
         return;
      }
      DeviceSelectionPlanner.Plan plan = this.deviceProfiles.plan(
         capabilities, this.profile, override, this.textureBudgetOverrides, packSetAudit);
      boolean automatic = plan.automatic();
      if (automatic && this.variantManager != null && !packSetAudit.preparedPackStackMatches()) {
         this.logger().warning("Skipped optimized profiles for " + connection.bedrockUsername()
            + ": this session's required packs differ from the prepared set. Original packs will be sent."
            + " Prepared UUIDs=" + (prewarmedPackSetAudit == null ? List.of()
               : prewarmedPackSetAudit.allPacks().stream().map(ResourcePack::uuid).toList())
            + " hashes=" + (prewarmedPackSetAudit == null ? List.of() : prewarmedPackSetAudit.sourceHashes())
            + "; session UUIDs=" + packSetAudit.allPacks().stream().map(ResourcePack::uuid).toList()
            + " hashes=" + packSetAudit.sourceHashes() + ".");
      }
      DeviceProfilePolicy.Selection selection = plan.selection();
      Map<java.util.UUID, PackProfile> selectedProfiles = new java.util.LinkedHashMap<>();
      Map<java.util.UUID, ResourcePack> replacements = new java.util.LinkedHashMap<>();
      List<ResourcePack> originalStack = List.copyOf(event.resourcePacks());
      boolean variantsReady = true;

      for (ResourcePack original : plan.packSetAudit().targetPacks()) {
         PackProfile selected = selection.profiles().getOrDefault(original.uuid(), PackProfile.FULL);
         ResourcePack replacement = original;
         try {
            if (selected != PackProfile.FULL) replacement = this.preparedVariant(plan.packSetAudit(), original, selected);
            if (replacement == null) {
               this.logger().warning("Could not load the selected " + selected.name() + " variant for pack "
                  + original.uuid() + ". Keeping the complete original target pack set.");
               variantsReady = false;
               selected = PackProfile.FULL;
               replacement = original;
            }
         } catch (RuntimeException | LinkageError failure) {
            this.logger().warning("Selection failed for pack " + original.uuid() + " ("
               + failure.getClass().getSimpleName() + "). Keeping the complete original target pack set.");
            variantsReady = false;
            selected = PackProfile.FULL;
            replacement = original;
         }
         replacements.put(original.uuid(), replacement);
         selectedProfiles.put(original.uuid(), selected);
      }

      if (!variantsReady) {
         for (ResourcePack original : plan.packSetAudit().targetPacks()) {
            replacements.put(original.uuid(), original);
            selectedProfiles.put(original.uuid(), PackProfile.FULL);
         }
      } else {
         try {
            if (!this.replacePacks(event, originalStack, replacements)) {
               this.logger().warning("Could not register the selected pack set for " + connection.bedrockUsername()
                  + ". Restored the original required pack stack.");
               selectedProfiles.replaceAll((uuid, ignored) -> PackProfile.FULL);
            }
         } catch (RuntimeException | LinkageError rollbackFailure) {
            this.logger().severe("Fettle could not restore the original pack stack for "
               + connection.bedrockUsername() + " after a registration failure.", rollbackFailure);
            return;
         }
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
         this.logger().warning("Fettle estimates up to " + snapshot.estimatedTextureHandleUpperBound()
            + " texture handles across the required packs for " + connection.bedrockUsername()
            + ". Resizing does not reduce handle count. Creator Tools' add-on guidance is "
            + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE + ".");
      }
      if (automatic && !snapshot.atlasMetricsComplete()) {
         this.logger().warning("Could not scan all atlas references for " + connection.bedrockUsername()
            + ". AUTO cannot confirm Fettle's conservative 256 MiB limit for the full pack set.");
      } else if (automatic && !snapshot.withinStackAtlasPolicy()) {
         this.logger().warning("The selected packs for " + connection.bedrockUsername()
            + " have estimated item/block atlas allocations of "
            + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedItemAtlasTextureBytes()))
            + "/" + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedBlockAtlasTextureBytes()))
            + ". This exceeds Fettle's conservative 256 MiB limit per atlas type. Creator Tools checks packs individually; "
            + "this combined estimate is not a verified Bedrock limit.");
      }
      if (automatic && !snapshot.budgetKnown()) {
         String tier = plan.selectionMemoryTier() == null
            ? "no usable MemoryTier was reported"
            : "no texture budget is configured for MemoryTier " + plan.selectionMemoryTier();
         String selected = selectedProfiles.values().stream().distinct().map(PackProfile::name).sorted().toList().toString();
         this.logger().warning("For " + connection.bedrockUsername() + ", " + tier
            + " (clientDataDecoded=" + capabilities.getClientDataDecoded()
            + ", DeviceOS=" + capabilities.getDeviceOs()
            + ", MemoryTierClaim=" + capabilities.getMemoryTierClaimStatus()
            + "). AUTO selected " + selected + " using a conservative "
            + this.humanBytes(snapshot.textureBudgetBytes()) + " cap. This is not a device measurement.");
      } else if (automatic && !snapshot.withinBudget()) {
         this.logger().warning(
            "Selected packs for " + connection.bedrockUsername() + " use an estimated "
               + this.humanBytes(snapshot.estimatedResourceMemoryBytes()) + " for decoded textures and rounded atlases, against a "
               + this.humanBytes(snapshot.textureBudgetBytes())
               + (plan.defaultTierBudget() ? " automatic MemoryTier budget" : " configured MemoryTier budget")
               + (snapshot.metricsComplete() ? "." : ". Measurement is incomplete.")
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
         this.logger().info("Geyser initialized " + connection.bedrockUsername() + " after Fettle selected profiles "
            + snapshot.selectedProfiles().values().stream().distinct().map(PackProfile::name).sorted().toList()
            + " " + memoryTier + ". This does not confirm pack rendering.");
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
            .description("Show the global profile and current Bedrock session data.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               DeviceProfileRuntime.Summary summary = this.deviceProfiles.summary();
               source.sendMessage("§aFettle status");
               source.sendMessage("§7Global profile: §f" + this.profile.name());
               source.sendMessage("§7Cached pack audits and variants: §f" + (this.variantManager == null ? 0 : this.variantManager.size()));
               source.sendMessage("§7Tracked Bedrock sessions: §f" + summary.trackedSessions()
                  + "§7, no usable MemoryTier: §f" + summary.missingMemoryTier()
                  + "§7, unreadable client data: §f" + summary.undecodedClientData());
               source.sendMessage("§7MemoryTier counts: §f" + summary.memoryTiers());
               source.sendMessage("§7MemoryTier by platform, game version, and protocol: §f" + summary.memoryTiersByPlatform());
               source.sendMessage("§7Missing MemoryTier by platform, game version, and protocol: §f"
                  + summary.missingMemoryTierByPlatform()
                  + "§7, unknown platform: §f" + summary.missingMemoryTierUnknownPlatform());
               source.sendMessage("§7Unusable MemoryTier claims: §f" + summary.memoryTierFailures());
               source.sendMessage("§7Selected profile sets: §f" + summary.profileSets());
               source.sendMessage("§7Budget and atlas results by platform/version/protocol/graphics/tier/profile: §f"
                  + summary.selectionOutcomes());
               source.sendMessage("§7Waiting for Geyser initialization: §f" + summary.pendingInitialization());
               source.sendMessage("§7Pack-selection attempts since startup: §f" + summary.packJoinAttemptCounts());
               source.sendMessage("§7Joins after Geyser initialization (not proof of rendering): §f"
                  + summary.postInitializationJoinCounts());
               source.sendMessage("§7Average time from selection to initialization (ms): §f"
                  + summary.postInitializationAverageMillis());
               source.sendMessage("§7Disconnects before initialization (not proof of a crash): §f"
                  + summary.preInitializationDisconnectCounts());
               source.sendMessage("§7Resource-pack-reason disconnects (not proof of a crash): §f"
                  + summary.resourcePackReasonDisconnectCounts());
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("device")
            .description("Show an online player's reported device and pack data.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUsage: /fettle device <online-gamertag|xuid>");
                  return;
               }
               String xuid = this.resolveXuid(args[0]);
               GeyserConnection connection = xuid.isEmpty() ? null : this.geyserApi().connectionByXuid(xuid);
               SessionDeviceSnapshot snapshot = connection == null ? null : this.deviceProfiles.session(connection);
               BedrockDeviceCapabilities capabilities = snapshot == null ? null : snapshot.capabilities();
               if (capabilities == null) {
                  source.sendMessage("§eNo pack-selection data is available for that online player.");
                  return;
               }
               String model = capabilities.getDeviceModel() == null
                  ? "unknown"
                  : capabilities.getDeviceModel().replace("§", "");
               String textureBudget = snapshot.budgetKnown()
                  ? this.humanBytes(snapshot.textureBudgetBytes())
                  : snapshot.textureBudgetBytes() > 0
                     ? "unknown (AUTO used a conservative cap of " + this.humanBytes(snapshot.textureBudgetBytes()) + ")"
                  : capabilities.getClientDataDecoded()
                     ? "unknown (client data has no usable MemoryTier)"
                     : "unknown (client data is missing or unreadable)";
               String animationVariant = snapshot.scaffoldingAnimationVariant() == null
                  ? "not present"
                  : snapshot.scaffoldingAnimationVariant();
               String handleGuidance = !snapshot.textureHandleMetricsComplete() ? "unknown"
                  : snapshot.estimatedTextureHandleUpperBound() <= PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE
                     ? "within " + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE
                     : "above " + PackAuditReport.CREATOR_TOOLS_ADDON_TEXTURE_HANDLE_GUIDANCE;
               String budgetSource = snapshot.defaultTierBudget() ? "automatic MemoryTier budget"
                  : snapshot.budgetKnown() ? "configured MemoryTier budget"
                     : snapshot.textureBudgetBytes() > 0 ? "conservative cap for an unknown tier" : "no usable MemoryTier";
               String budgetFit = snapshot.budgetKnown() && snapshot.metricsComplete()
                  ? Boolean.toString(snapshot.withinBudget()) : "unknown";
               long packFileCount = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null)
                  .mapToLong(pack -> pack.report().getFileCount())
                  .sum();
               boolean packFileCountComplete = snapshot.packAudits().stream().allMatch(pack -> pack.report() != null);
               source.sendMessage("§aBedrock client report for §f" + xuid);
               source.sendMessage("§7Platform: §f" + capabilities.getPlatform()
                  + "§7, client data decoded: §f" + capabilities.getClientDataDecoded()
                  + "§7, DeviceOS: §f" + capabilities.getDeviceOs()
                  + (capabilities.getDeviceOsLabel() == null ? "" : " (" + capabilities.getDeviceOsLabel() + ")")
                  + "§7, model: §f" + model);
               source.sendMessage("§7Game version: §f" + capabilities.getGameVersion()
                  + "§7, Geyser protocol: §f" + snapshot.geyserProtocolVersion()
                  + "§7, reported MemoryTier: §f" + capabilities.getMemoryTier()
                  + " (" + capabilities.getMemoryTierDescription() + ", " + capabilities.getMemoryTierClaimStatus() + ")");
               source.sendMessage("§7View distance: §f" + capabilities.getMaxViewDistance()
                  + "§7, graphics mode: §f" + capabilities.getGraphicsMode()
                  + "§7, client-side chunk generation: §f" + capabilities.getCompatibleWithClientSideChunkGen());
               source.sendMessage("§7Selection MemoryTier: §f" + snapshot.selectionMemoryTier()
                  + "§7, Scaffolding animation: §f" + animationVariant
                  + "§7, selected profiles: §f" + snapshot.selectedProfiles());
               source.sendMessage("§7Pack archive sizes: §f" + snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null)
                  .map(pack -> pack.name().replace("§", "") + " (" + pack.profile() + "): "
                     + this.humanBytes(pack.report().getArchiveBytes()))
                  .toList());
               source.sendMessage("§7Pack files: §f~" + packFileCount + (packFileCountComplete ? "" : " (partial)")
                  + "§7, texture files: §f~" + snapshot.estimatedTextureFileCount()
                  + (snapshot.metricsComplete() ? "" : " (partial)"));
               source.sendMessage("§7Decoded texture estimate: §f" + this.humanBytes(snapshot.estimatedTextureBytes())
                  + "§7, texture plus rounded atlases: §f" + this.humanBytes(snapshot.estimatedResourceMemoryBytes()));
               source.sendMessage("§7Rounded atlas estimates for all required packs: item §f"
                  + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedItemAtlasTextureBytes()))
                  + "§7, block §f"
                  + this.humanBytes(PackAuditReport.roundedAtlasAllocationBytes(snapshot.estimatedBlockAtlasTextureBytes()))
                  + "§7. Scan: §f" + (snapshot.atlasMetricsComplete() ? "complete" : "partial")
                  + "§7, within Fettle's limit: §f"
                  + (snapshot.atlasMetricsComplete() ? Boolean.toString(snapshot.withinStackAtlasPolicy()) : "unknown"));
               source.sendMessage("§7Texture handles: up to §f" + snapshot.estimatedTextureHandleUpperBound()
                  + (snapshot.textureHandleMetricsComplete() ? "" : " (partial)")
                  + "§7, Creator Tools guidance: §f" + handleGuidance);
               source.sendMessage("§7Texture budget: §f" + textureBudget + "§7, source: §f" + budgetSource
                  + "§7, fit: §f" + budgetFit);
               List<PackSetAudit.PackAuditSnapshot> largestModels = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getLargestModelPath() != null)
                  .sorted(java.util.Comparator.comparingInt(
                     (PackSetAudit.PackAuditSnapshot pack) -> pack.report().getMaxModelCubes()).reversed())
                  .limit(3)
                  .toList();
               if (!largestModels.isEmpty()) {
                  source.sendMessage("§7Largest static models (separate from texture estimates and live entities):");
                  largestModels.forEach(pack -> {
                     PackAuditReport report = pack.report();
                     source.sendMessage("§7" + pack.name().replace("§", "") + " (" + pack.profile() + "): "
                        + report.getModelFileCount() + " models; largest is "
                        + report.getLargestModelPath().replace("§", "") + " ("
                        + report.getMaxModelCubes() + " cubes).");
                  });
               }
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null)
                  .forEach(pack -> pack.report().getWarnings().stream()
                           .filter(warning -> warning.contains("block model file") && warning.contains("50 cubes"))
                     .forEach(warning -> source.sendMessage("§eCustom block geometry in "
                        + pack.name().replace("§", "") + " (" + pack.profile() + "): " + warning)));
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getRootFontGlyphCount() > 0)
                  .forEach(pack -> source.sendMessage("§7" + pack.report().getRootFontGlyphCount()
                     + " root font pages in " + pack.name().replace("§", "") + " (" + pack.profile() + "), "
                     + this.humanBytes(pack.report().getRootFontGlyphTextureMemoryBytes())
                     + " measured. These pages are outside the documented path and counted conservatively."));
               snapshot.packAudits().stream()
                  .filter(pack -> pack.report() != null && pack.report().getOversizedModelCount() > 0)
                  .forEach(pack -> source.sendMessage("§eModel scan stopped early for "
                     + pack.name().replace("§", "") + ": " + pack.report().getOversizedModelCount()
                     + " model files exceeded the scan limit."));
               List<PackSetAudit.PackAuditSnapshot> incompletePacks = snapshot.packAudits().stream()
                  .filter(pack -> pack.report() == null || !pack.report().getTextureMetricsComplete())
                  .toList();
               if (!incompletePacks.isEmpty()) {
                  incompletePacks.forEach(pack -> {
                     PackAuditReport report = pack.report();
                     source.sendMessage("§eIncomplete measurements for §f" + pack.name() + " §7(" + pack.id() + ", "
                        + pack.profile() + "): §e" + (report == null ? "no audit is available."
                           : report.getUnmeasuredTextureCount() + " textures could not be measured; known decoded-texture estimate: "
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
                     .forEach(pack -> source.sendMessage("§7Largest texture estimate: §f" + pack.name() + " §7("
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
            .description("Set Fettle's global pack profile.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUsage: /fettle optimize <auto|full|semi|mini|safe>");
               } else {
                  PackProfile requested = this.parseProfile(args[0]);
                  if (requested != null && requested != PackProfile.FULL) {
                     this.profile = requested;
                     if (!this.saveConfig()) {
                        source.sendMessage("§cProfile changed in memory, but Fettle could not save config.properties. A restart will restore the saved profile.");
                        return;
                     }
                     source.sendMessage("§aFettle profile set to §f" + this.profile.name() + "§a. Reconnect to apply it.");
                  } else {
                     source.sendMessage("§cChoose auto, full, semi, mini, or safe.");
                  }
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("audit")
            .description("Show measurements for cached pack variants.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor(
               (source, command, args) -> {
                  List<PackVariantManager.PreparedVariant> audits = this.variantManager == null
                     ? List.of()
                     : this.variantManager.cachedVariants();
                  if (audits.isEmpty()) {
                     source.sendMessage("§eFettle has not prepared any pack audits or variants yet.");
                  } else {
                     audits
                        .stream()
                        .sorted(java.util.Comparator.comparing((PackVariantManager.PreparedVariant pack) -> pack.pack().uuid().toString())
                           .thenComparing(pack -> pack.profile().name()))
                        .forEach(cached -> {
                           PackAuditReport report = cached.audit();
                           String kind = this.matchesTargetPack(cached.pack()) ? cached.profile().name() : "FIXED";
                           source.sendMessage(
                              "§aFettle " + kind + " §7(" + cached.pack().uuid() + "): §f"
                                 + report.getFileCount() + " files; " + report.getTextureFileCount() + " textures; "
                                 + report.getEstimatedTextureHandleCount() + " estimated texture handles; archive size "
                                 + this.humanBytes(report.getArchiveBytes()) + "; decoded-texture estimate "
                                 + String.format(Locale.ROOT, "%.1f MiB", report.getTextureMemoryMiB())
                                 + "; " + report.getModelFileCount() + " models, up to "
                                 + report.getMaxModelCubes() + " cubes; texture scan "
                                 + (report.getTextureMetricsComplete() ? "complete." : "incomplete.")
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
                                 "§7Largest potential atlas texture: §f" + report.getLargestPotentialAtlasTexturePath().replace("§", "")
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
                              source.sendMessage("§7" + report.getRootFontGlyphCount() + " root font pages, "
                                 + this.humanBytes(report.getRootFontGlyphTextureMemoryBytes())
                                 + " measured. These pages are outside the documented path and counted conservatively.");
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
            .description("Set a Bedrock player's profile.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 2) {
                  source.sendMessage("§cUsage: /fettle set <xuid|online-gamertag> <auto|original|optimize-full|optimize-semi|optimize-mini|safe>");
               } else {
                  String xuid = this.resolveXuid(args[0]);
                  PackProfile requested = this.parseOverrideProfile(args[1]);
                  if (xuid.isEmpty()) {
                     source.sendMessage("§cThat player is offline, and the argument is not a numeric XUID.");
                  } else if (requested == null) {
                     source.sendMessage("§cChoose auto, original, optimize-full, optimize-semi, optimize-mini, or safe.");
                  } else {
                     this.playerProfiles.put(xuid, requested);
                     if (!this.saveProfiles()) {
                        source.sendMessage("§cProfile set in memory, but Fettle could not save profiles.properties. A restart will restore the saved value.");
                        return;
                     }
                     source.sendMessage("§aSet §f" + xuid + "§a to §f" + requested.name() + "§a. That player must reconnect for it to apply.");
                     GeyserConnection online = this.geyserApi().connectionByXuid(xuid);
                     if (online != null) {
                        online.sendMessage("§eYour Fettle profile is now §f" + requested.name() + "§e. Reconnect to apply it.");
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
            .description("Remove a Bedrock player's profile override.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               if (args.length != 1) {
                  source.sendMessage("§cUsage: /fettle unset <xuid|online-gamertag>");
               } else {
                  String xuid = this.resolveXuid(args[0]);
                  if (xuid.isEmpty()) {
                     source.sendMessage("§cThat player is offline, and the argument is not a numeric XUID.");
                  } else {
                     this.playerProfiles.remove(xuid);
                     if (!this.saveProfiles()) {
                        source.sendMessage("§cOverride removed in memory, but Fettle could not save profiles.properties. It may return after restart.");
                        return;
                     }
                     source.sendMessage("§aRemoved Fettle's profile override for §f" + xuid + "§a.");
                  }
               }
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("full")
            .description("Send original required packs without optimization.")
            .permission("fettle.admin")
            .playerOnly(false)
            .executor((source, command, args) -> {
               this.profile = PackProfile.FULL;
               if (!this.saveConfig()) {
                  source.sendMessage("§cOriginal-pack mode is active in memory, but Fettle could not save config.properties. A restart will restore the saved profile.");
                  return;
               }
               source.sendMessage("§aFettle now sends original packs unchanged.");
            })
            .build()
      );
      event.register(
         Command.builder(this)
            .source(CommandSource.class)
            .name("reload")
            .description("Reload Fettle's configuration and player profiles.")
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
                     + ". Fettle kept the current in-memory values for those files.");
                  return;
               }
               source.sendMessage(activeMatches.equals(this.matchNameTokens)
                  ? "§aFettle profiles and budgets reloaded."
                  : "§aFettle profiles and budgets reloaded. Restart Geyser to apply match-name changes and prepare variants.");
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

   boolean replacePacks(
      SessionLoadResourcePacksEvent event,
      List<ResourcePack> originalStack,
      Map<java.util.UUID, ResourcePack> replacements
   ) {
      if (!samePackOrder(event.resourcePacks(), originalStack)) return false;
      Map<java.util.UUID, List<ResourcePackOption<?>>> options = new java.util.LinkedHashMap<>();
      List<ResourcePack> desiredStack = new ArrayList<>(originalStack.size());
      java.util.Set<java.util.UUID> desiredIds = new java.util.HashSet<>();
      boolean changed = false;
      try {
         for (ResourcePack original : originalStack) {
            List<ResourcePackOption<?>> packOptions = event.options(original.uuid()).stream()
               .filter(Objects::nonNull).toList();
            options.put(original.uuid(), packOptions);
            ResourcePack desired = replacements.getOrDefault(original.uuid(), original);
            if (!desiredIds.add(desired.uuid())) return false;
            for (ResourcePackOption<?> option : packOptions) option.validate(desired);
            desiredStack.add(desired);
            changed |= desired != original;
         }
      } catch (RuntimeException | LinkageError invalidOptions) {
         return false;
      }
      if (!changed) return true;

      try {
         for (ResourcePack original : originalStack) {
            if (!event.unregister(original.uuid())) throw new IllegalStateException("Could not remove pack " + original.uuid());
         }
         for (int index = 0; index < desiredStack.size(); index++) {
            ResourcePack desired = desiredStack.get(index);
            ResourcePack original = originalStack.get(index);
            event.register(desired, options.get(original.uuid()).toArray(ResourcePackOption<?>[]::new));
         }
         if (!samePackOrder(event.resourcePacks(), desiredStack)) {
            throw new IllegalStateException("Geyser changed the registered pack order");
         }
         return true;
      } catch (RuntimeException | LinkageError registrationFailure) {
         try {
            for (ResourcePack current : List.copyOf(event.resourcePacks())) event.unregister(current.uuid());
            if (!event.resourcePacks().isEmpty()) throw new IllegalStateException("Could not clear the failed pack set");
            for (int index = 0; index < originalStack.size(); index++) {
               ResourcePack original = originalStack.get(index);
               event.register(original, options.get(original.uuid()).toArray(ResourcePackOption<?>[]::new));
            }
            if (!samePackOrder(event.resourcePacks(), originalStack)) {
               throw new IllegalStateException("Geyser changed the restored pack order");
            }
            return false;
         } catch (RuntimeException | LinkageError restoreFailure) {
            restoreFailure.addSuppressed(registrationFailure);
            throw new IllegalStateException("Could not restore the original Geyser resource-pack stack", restoreFailure);
         }
      }
   }

   private static boolean samePackOrder(Collection<ResourcePack> actual, List<ResourcePack> expected) {
      return actual.stream().map(ResourcePack::uuid).toList()
         .equals(expected.stream().map(ResourcePack::uuid).toList());
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
               this.logger().warning("Ignored invalid " + key + " value. Enter a number from 0 to 8192 MiB.");
            } else {
               budgets.put(tier, bytes);
            }
         }
         this.profile = loadedProfile;
         this.matchNameTokens = loadedMatchNameTokens;
         this.textureBudgetOverrides = Map.copyOf(budgets);
         return true;
      } catch (Throwable throwable) {
         this.logger().severe("Could not load config.properties.", throwable);
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
            properties.store(output, "Fettle pack optimizer settings");
         }

         try {
            Files.move(temp, this.configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, this.configFile, StandardCopyOption.REPLACE_EXISTING);
         }
         return true;
      } catch (IOException exception) {
         this.logger().warning("Could not save config.properties: " + exception.getMessage());
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
         this.logger().warning("Could not load profiles.properties. Keeping current profiles: " + exception.getMessage());
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
            properties.store(output, "Fettle player profile overrides");
         }

         try {
            Files.move(temp, this.profilesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, this.profilesFile, StandardCopyOption.REPLACE_EXISTING);
         }
         return true;
      } catch (IOException exception) {
         this.logger().warning("Could not save profiles.properties: " + exception.getMessage());
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
