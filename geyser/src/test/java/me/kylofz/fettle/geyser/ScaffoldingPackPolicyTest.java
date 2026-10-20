package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScaffoldingPackPolicyTest {
    @Test
    void reportsTheAnimationPackScaffoldingSelectedWithoutReinterpretingIt() {
        ResourcePack primary = pack("Scaffolding Animated");
        ResourcePack simple = pack("Scaffolding Animated Simple");

        assertEquals("SIMPLE", ScaffoldingPackPolicy.selectedAnimation(List.of(simple)));
        assertEquals("FULL", ScaffoldingPackPolicy.selectedAnimation(List.of(primary)));
        assertNull(ScaffoldingPackPolicy.selectedAnimation(List.of()));
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
