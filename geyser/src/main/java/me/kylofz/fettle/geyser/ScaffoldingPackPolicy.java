package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.ResourcePack;

import java.util.Collection;
import java.util.List;

final class ScaffoldingPackPolicy {
    static final String ANIMATED_PACK = "Scaffolding Animated";
    static final String SIMPLE_PACK = "Scaffolding Animated Simple";
    static final int FULL_ANIMATION_MIN_MEMORY_TIER = 4;

    private ScaffoldingPackPolicy() {
    }

    static ResourcePack find(Collection<ResourcePack> packs, String name) {
        if (packs == null) return null;
        return packs.stream().filter(pack -> name.equalsIgnoreCase(name(pack))).findFirst().orElse(null);
    }

    static String selectedAnimation(Collection<ResourcePack> packs) {
        AnimationVariant variant = registeredVariant(packs);
        return variant == AnimationVariant.NONE ? null : variant.name();
    }

    static AnimationVariant registeredVariant(Collection<ResourcePack> packs) {
        boolean simple = find(packs, SIMPLE_PACK) != null;
        boolean full = find(packs, ANIMATED_PACK) != null;
        if (simple && full) return AnimationVariant.BOTH;
        if (simple) return AnimationVariant.SIMPLE;
        if (full) return AnimationVariant.FULL;
        return AnimationVariant.NONE;
    }

    static Selection currentSelection(Collection<ResourcePack> packs) {
        ResourcePack full = find(packs, ANIMATED_PACK);
        ResourcePack simple = find(packs, SIMPLE_PACK);
        if (full != null && simple != null) return new Selection(AnimationVariant.BOTH, null, null);
        if (simple != null) return new Selection(AnimationVariant.SIMPLE, simple, null);
        if (full != null) return new Selection(AnimationVariant.FULL, full, null);
        return new Selection(AnimationVariant.NONE, null, null);
    }

    static Selection selectForMemoryTier(Collection<ResourcePack> packs, Integer memoryTier) {
        ResourcePack full = find(packs, ANIMATED_PACK);
        ResourcePack simple = find(packs, SIMPLE_PACK);
        if (full == null && simple == null) return new Selection(AnimationVariant.NONE, null, null);
        if (full == null) return new Selection(AnimationVariant.SIMPLE, simple, null);
        if (simple == null) return new Selection(AnimationVariant.FULL, full, null);

        // shortcut: Simple has lower cube counts in the checked split set; re-audit after Scaffolding updates.
        boolean preferFull = memoryTier != null && memoryTier >= FULL_ANIMATION_MIN_MEMORY_TIER;
        return preferFull
                ? new Selection(AnimationVariant.FULL, full, simple)
                : new Selection(AnimationVariant.SIMPLE, simple, full);
    }

    static List<ResourcePack> stackWithout(Collection<ResourcePack> packs, ResourcePack omitted) {
        return packs.stream().filter(pack -> !omitted.uuid().equals(pack.uuid())).toList();
    }

    enum AnimationVariant {
        NONE,
        FULL,
        SIMPLE,
        BOTH
    }

    record Selection(AnimationVariant variant, ResourcePack selected, ResourcePack omitted) {
    }

    private static String name(ResourcePack pack) {
        try {
            return pack.manifest().header().name().trim();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }
}
