package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.ResourcePack;

import java.util.Collection;

final class ScaffoldingPackPolicy {
    static final String ANIMATED_PACK = "Scaffolding Animated";
    static final String SIMPLE_PACK = "Scaffolding Animated Simple";

    private ScaffoldingPackPolicy() {
    }

    static ResourcePack find(Collection<ResourcePack> packs, String name) {
        if (packs == null) return null;
        return packs.stream().filter(pack -> name.equalsIgnoreCase(name(pack))).findFirst().orElse(null);
    }

    static String selectedAnimation(Collection<ResourcePack> packs) {
        if (find(packs, SIMPLE_PACK) != null) return "SIMPLE";
        if (find(packs, ANIMATED_PACK) != null) return "FULL";
        return null;
    }

    private static String name(ResourcePack pack) {
        try {
            return pack.manifest().header().name().trim();
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }
}
