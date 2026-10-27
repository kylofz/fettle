package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;
import org.geysermc.geyser.api.pack.option.ResourcePackOption.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FettlePackReplacementTest {
    private static final UUID PACK_ID = UUID.fromString("42ab4cb8-76d5-4f0f-8ca6-3788c23665e2");

    @Test
    void anUnavailableOptimizedProfileCannotFallBackToTheOriginalPack() {
        assertFalse(FettleExtension.mayFallbackToOriginal(false, List.of(PackProfile.SAFE)));
        assertFalse(FettleExtension.mayFallbackToOriginal(
                false, List.of(PackProfile.FULL, PackProfile.OPTIMIZE_MINI)));
        assertFalse(FettleExtension.mayFallbackToOriginal(true, List.of(PackProfile.FULL)));
        assertTrue(FettleExtension.mayFallbackToOriginal(false, List.of(PackProfile.FULL)));
    }

    @Test
    void failedAutomaticSelectionTellsThePlayerBeforeGeyserDisconnects() {
        AtomicReference<String> message = new AtomicReference<>();
        GeyserConnection connection = connection(message);
        ResourcePack target = pack(PACK_ID, "Scaffolding Base");
        FakeEvent event = new FakeEvent(connection, List.of(target));

        assertThrows(IllegalStateException.class, () -> new FettleExtension().onLoadResourcePacks(event));
        assertEquals(
                "§cFettle could not prepare or validate this server's required resource packs. Contact the server administrator.",
                message.get());
        assertEquals(List.of(target), event.resourcePacks());
    }

    @Test
    void vibrantVisualsIsDisabledOnlyWhenTheOperatorConfiguresIt() throws Exception {
        FettleExtension extension = new FettleExtension();
        Field setting = FettleExtension.class.getDeclaredField("disableVibrantVisuals");
        setting.setAccessible(true);
        setting.set(extension, true);
        FakeEvent event = new FakeEvent(connection(), List.of());

        extension.onLoadResourcePacks(event);

        assertFalse(event.vibrantVisualsAllowed);
    }

    @Test
    void replacementKeepsTheOriginalSubpackSelection() throws Exception {
        ResourcePack original = pack(PACK_ID);
        ResourcePack replacement = pack(PACK_ID);
        ResourcePackOption<?> subpack = option(Type.SUBPACK, "low_memory");
        FakeEvent event = new FakeEvent(connection(), original, List.of(subpack));

        boolean replaced = replace(new FettleExtension(), event, original, replacement);

        assertTrue(replaced);
        assertSame(replacement, event.packs.get(PACK_ID));
        assertEquals(List.of(subpack), event.options.get(PACK_ID));
    }

    @Test
    void replacementPreservesTheOriginalPackOrder() throws Exception {
        ResourcePack first = pack(UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack original = pack(PACK_ID);
        ResourcePack last = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack replacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, original, last));

        assertTrue(replace(new FettleExtension(), event, original, replacement));

        assertEquals(List.of(first, replacement, last), event.resourcePacks());
    }

    @Test
    void failedLaterReplacementRestoresTheWholeLinkedGroup() throws Exception {
        ResourcePack first = pack(PACK_ID);
        UUID secondId = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        ResourcePack second = pack(secondId);
        ResourcePack firstReplacement = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack secondReplacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, second));
        event.failPack = secondReplacement;

        assertFalse(new FettleExtension().replacePacks(
                event,
                List.of(first, second),
                Map.of(first.uuid(), firstReplacement, second.uuid(), secondReplacement)));

        assertEquals(List.of(first, second), event.resourcePacks());
    }

    @Test
    void failedFirstReplacementRestoresTheWholeLinkedGroup() throws Exception {
        ResourcePack first = pack(PACK_ID);
        UUID secondId = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        ResourcePack second = pack(secondId);
        ResourcePack firstReplacement = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack secondReplacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, second));
        event.failPack = firstReplacement;

        assertFalse(new FettleExtension().replacePacks(
                event,
                List.of(first, second),
                Map.of(first.uuid(), firstReplacement, second.uuid(), secondReplacement)));

        assertEquals(List.of(first, second), event.resourcePacks());
    }

    @Test
    void failedUnregisterRestoresTheOriginalStack() throws Exception {
        ResourcePack first = pack(PACK_ID);
        UUID secondId = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        ResourcePack second = pack(secondId);
        ResourcePack firstReplacement = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack secondReplacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, second));
        event.failUnregisterOnce = first.uuid();

        assertFalse(new FettleExtension().replacePacks(
                event,
                List.of(first, second),
                Map.of(first.uuid(), firstReplacement, second.uuid(), secondReplacement)));

        assertEquals(List.of(first, second), event.resourcePacks());
    }

    @Test
    void rollbackClearFailurePropagatesInsteadOfLeavingAnAcceptedPartialStack() {
        ResourcePack first = pack(PACK_ID);
        UUID secondId = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        ResourcePack second = pack(secondId);
        ResourcePack firstReplacement = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack secondReplacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, second));
        event.failPack = secondReplacement;
        event.failUnregisterOnce = firstReplacement.uuid();

        assertThrows(IllegalStateException.class, () -> new FettleExtension().replacePacks(
                event,
                List.of(first, second),
                Map.of(first.uuid(), firstReplacement, second.uuid(), secondReplacement)));
        assertEquals(List.of(firstReplacement), event.resourcePacks());
    }

    @Test
    void failedReplacementRestoresTheRequiredOriginalPackWithItsOptions() throws Exception {
        ResourcePack original = pack(PACK_ID);
        ResourcePack replacement = pack(PACK_ID);
        ResourcePackOption<?> subpack = option(Type.SUBPACK, "low_memory");
        FakeEvent event = new FakeEvent(connection(), original, List.of(subpack));
        event.failPack = replacement;

        boolean replaced = replace(new FettleExtension(), event, original, replacement);

        assertFalse(replaced);
        assertSame(original, event.packs.get(PACK_ID));
        assertEquals(List.of(subpack), event.options.get(PACK_ID));
    }

    @Test
    void rollbackFailureIsPropagatedInsteadOfReturningWithAPartialRequiredStack() {
        ResourcePack first = pack(PACK_ID);
        UUID secondId = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        ResourcePack second = pack(secondId);
        ResourcePack firstReplacement = pack(UUID.fromString("62ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        ResourcePack secondReplacement = pack(UUID.fromString("72ab4cb8-76d5-4f0f-8ca6-3788c23665e2"));
        FakeEvent event = new FakeEvent(connection(), List.of(first, second));
        event.failPack = secondReplacement;
        event.failRestorePack = second;

        assertThrows(IllegalStateException.class, () -> new FettleExtension().replacePacks(
                event,
                List.of(first, second),
                Map.of(first.uuid(), firstReplacement, second.uuid(), secondReplacement)));
        assertEquals(List.of(first), event.resourcePacks());
    }

    private static boolean replace(FettleExtension extension, FakeEvent event, ResourcePack original, ResourcePack replacement) {
        return extension.replacePacks(event, event.resourcePacks(), Map.of(original.uuid(), replacement));
    }

    private static ResourcePack pack(UUID id) {
        return (ResourcePack) Proxy.newProxyInstance(
                ResourcePack.class.getClassLoader(),
                new Class<?>[]{ResourcePack.class},
                (proxy, method, args) -> method.getName().equals("uuid") ? id : defaultValue(method.getReturnType()));
    }

    private static ResourcePack pack(UUID id, String name) {
        ResourcePackManifest.Header header = (ResourcePackManifest.Header) Proxy.newProxyInstance(
                ResourcePackManifest.Header.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.Header.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uuid" -> id;
                    case "name" -> name;
                    default -> defaultValue(method.getReturnType());
                });
        ResourcePackManifest manifest = (ResourcePackManifest) Proxy.newProxyInstance(
                ResourcePackManifest.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.class},
                (proxy, method, args) -> method.getName().equals("header")
                        ? header : defaultValue(method.getReturnType()));
        return (ResourcePack) Proxy.newProxyInstance(
                ResourcePack.class.getClassLoader(),
                new Class<?>[]{ResourcePack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uuid" -> id;
                    case "manifest" -> manifest;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ResourcePackOption<?> option(Type type, Object value) {
        return (ResourcePackOption<?>) Proxy.newProxyInstance(
                ResourcePackOption.class.getClassLoader(),
                new Class<?>[]{ResourcePackOption.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "value" -> value;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static GeyserConnection connection() {
        return connection(new AtomicReference<>());
    }

    private static GeyserConnection connection(AtomicReference<String> message) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("sendMessage")) {
                message.set((String) args[0]);
                return null;
            }
            return defaultValue(method.getReturnType());
        };
        return (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(),
                new Class<?>[]{GeyserConnection.class},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0;
        if (type == char.class) return '\0';
        return null;
    }

    private static final class FakeEvent extends SessionLoadResourcePacksEvent {
        private final Map<UUID, ResourcePack> packs = new LinkedHashMap<>();
        private final Map<UUID, List<ResourcePackOption<?>>> options = new LinkedHashMap<>();
        private ResourcePack failPack;
        private ResourcePack failRestorePack;
        private UUID failUnregisterOnce;
        private boolean vibrantVisualsAllowed = true;

        private FakeEvent(GeyserConnection connection, ResourcePack original, List<ResourcePackOption<?>> initialOptions) {
            super(connection);
            register(original, initialOptions.toArray(ResourcePackOption<?>[]::new));
        }

        private FakeEvent(GeyserConnection connection, List<ResourcePack> initialPacks) {
            super(connection);
            initialPacks.forEach(pack -> register(pack, new ResourcePackOption<?>[0]));
        }

        @Override
        public List<ResourcePack> resourcePacks() {
            return List.copyOf(packs.values());
        }

        @Override
        public boolean register(ResourcePack pack) {
            register(pack, new ResourcePackOption<?>[0]);
            return true;
        }

        @Override
        public void register(ResourcePack pack, ResourcePackOption<?>... packOptions) {
            if (packs.containsKey(pack.uuid())) throw new IllegalArgumentException("duplicate pack UUID");
            if (pack == failPack || pack == failRestorePack) {
                throw new IllegalArgumentException("test registration failure");
            }
            packs.put(pack.uuid(), pack);
            options.put(pack.uuid(), new ArrayList<>(List.of(packOptions)));
        }

        @Override
        public void registerOptions(UUID uuid, ResourcePackOption<?>... packOptions) {
            options.put(uuid, new ArrayList<>(List.of(packOptions)));
        }

        @Override
        public Collection<ResourcePackOption<?>> options(UUID uuid) {
            return options.getOrDefault(uuid, List.of());
        }

        @Override
        public ResourcePackOption<?> option(UUID uuid, Type type) {
            return options(uuid).stream().filter(option -> option.type() == type).findFirst().orElse(null);
        }

        @Override
        public boolean unregister(UUID uuid) {
            if (uuid.equals(failUnregisterOnce)) {
                failUnregisterOnce = null;
                return false;
            }
            options.remove(uuid);
            return packs.remove(uuid) != null;
        }

        @Override
        public void allowVibrantVisuals(boolean allow) {
            vibrantVisualsAllowed = allow;
        }
    }
}
