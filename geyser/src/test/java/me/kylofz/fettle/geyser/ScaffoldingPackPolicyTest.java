package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScaffoldingPackPolicyTest {
    @Test
    void reportsRegisteredAnimationVariants() {
        ResourcePack full = pack(ScaffoldingPackPolicy.ANIMATED_PACK);
        ResourcePack simple = pack(ScaffoldingPackPolicy.SIMPLE_PACK);

        assertEquals("SIMPLE", ScaffoldingPackPolicy.selectedAnimation(List.of(simple)));
        assertEquals("FULL", ScaffoldingPackPolicy.selectedAnimation(List.of(full)));
        assertEquals("BOTH", ScaffoldingPackPolicy.selectedAnimation(List.of(full, simple)));
        assertNull(ScaffoldingPackPolicy.selectedAnimation(List.of()));
    }

    @Test
    void selectsSimpleForLowOrMissingClientMemoryTier() {
        ResourcePack full = pack(ScaffoldingPackPolicy.ANIMATED_PACK);
        ResourcePack simple = pack(ScaffoldingPackPolicy.SIMPLE_PACK);
        List<ResourcePack> packs = List.of(full, simple);

        for (Integer tier : new Integer[]{null, 0, 1, 2, 3}) {
            ScaffoldingPackPolicy.Selection selection = ScaffoldingPackPolicy.selectForMemoryTier(packs, tier);

            assertEquals(ScaffoldingPackPolicy.AnimationVariant.SIMPLE, selection.variant());
            assertSame(simple, selection.selected());
            assertSame(full, selection.omitted());
        }
    }

    @Test
    void selectsFullOnlyForTheHighestConfiguredMemoryTiers() {
        ResourcePack full = pack(ScaffoldingPackPolicy.ANIMATED_PACK);
        ResourcePack simple = pack(ScaffoldingPackPolicy.SIMPLE_PACK);

        for (int tier = ScaffoldingPackPolicy.FULL_ANIMATION_MIN_MEMORY_TIER; tier <= 5; tier++) {
            ScaffoldingPackPolicy.Selection selection =
                    ScaffoldingPackPolicy.selectForMemoryTier(List.of(full, simple), tier);

            assertEquals(ScaffoldingPackPolicy.AnimationVariant.FULL, selection.variant());
            assertSame(full, selection.selected());
            assertSame(simple, selection.omitted());
        }
    }

    @Test
    void leavesTheOnlyRegisteredAnimationAlone() {
        ResourcePack simple = pack(ScaffoldingPackPolicy.SIMPLE_PACK);

        ScaffoldingPackPolicy.Selection selection =
                ScaffoldingPackPolicy.selectForMemoryTier(List.of(simple), 0);

        assertEquals(ScaffoldingPackPolicy.AnimationVariant.SIMPLE, selection.variant());
        assertSame(simple, selection.selected());
        assertNull(selection.omitted());
    }

    private static ResourcePack pack(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResourcePackManifest.Header header = (ResourcePackManifest.Header) Proxy.newProxyInstance(
                ResourcePackManifest.Header.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.Header.class},
                (proxy, method, args) -> method.getName().equals("name") ? name : null);
        ResourcePackManifest manifest = (ResourcePackManifest) Proxy.newProxyInstance(
                ResourcePackManifest.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.class},
                (proxy, method, args) -> method.getName().equals("header") ? header : null);
        return (ResourcePack) Proxy.newProxyInstance(
                ResourcePack.class.getClassLoader(),
                new Class<?>[]{ResourcePack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uuid" -> id;
                    case "manifest" -> manifest;
                    default -> null;
                });
    }
}
