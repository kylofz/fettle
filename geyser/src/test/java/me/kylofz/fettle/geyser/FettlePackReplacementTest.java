package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;
import org.geysermc.geyser.api.pack.option.ResourcePackOption.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FettlePackReplacementTest {
    private static final UUID PACK_ID = UUID.fromString("42ab4cb8-76d5-4f0f-8ca6-3788c23665e2");

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

    private static boolean replace(FettleExtension extension, FakeEvent event, ResourcePack original, ResourcePack replacement) throws Exception {
        Method method = FettleExtension.class.getDeclaredMethod(
                "replacePack", SessionLoadResourcePacksEvent.class, ResourcePack.class, ResourcePack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(extension, event, original, replacement);
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
        InvocationHandler handler = (proxy, method, args) -> defaultValue(method.getReturnType());
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
        private final Map<UUID, ResourcePack> packs = new HashMap<>();
        private final Map<UUID, List<ResourcePackOption<?>>> options = new HashMap<>();
        private ResourcePack failPack;

        private FakeEvent(GeyserConnection connection, ResourcePack original, List<ResourcePackOption<?>> initialOptions) {
            super(connection);
            register(original, initialOptions.toArray(ResourcePackOption<?>[]::new));
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
            if (pack == failPack) throw new IllegalArgumentException("test registration failure");
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
            options.remove(uuid);
            return packs.remove(uuid) != null;
        }

        @Override
        public void allowVibrantVisuals(boolean allow) {
        }
    }
}
