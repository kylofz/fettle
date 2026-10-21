package me.kylofz.fettle.geyser;

import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FettleEventOrderingTest {
    @Test
    void preparesAfterOtherPackProvidersAndSelectsAfterPerSessionPackFilters() throws Exception {
        assertEquals(PostOrder.LAST, subscribe("onDefineResourcePacks", GeyserDefineResourcePacksEvent.class).postOrder());
        assertEquals(PostOrder.LAST, subscribe("onLoadResourcePacks", SessionLoadResourcePacksEvent.class).postOrder());
        assertEquals(PostOrder.FIRST, subscribe("onSessionDisconnect", SessionDisconnectEvent.class).postOrder());
        assertNotNull(subscribe("onSessionJoin", SessionJoinEvent.class));
    }

    @Test
    void everyAutomaticVariantIsPreparedBeforeSessionsAreAccepted() {
        assertEquals(
                List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
                        PackProfile.OPTIMIZE_MINI, PackProfile.SAFE),
                PackVariantManager.PREWARM_PROFILES);
    }

    @Test
    void configSerializesOriginalAndLosslessFullProfilesDistinctly() {
        FettleExtension extension = new FettleExtension();
        for (PackProfile profile : PackProfile.values()) {
            assertEquals(profile, extension.parseProfile(FettleExtension.profileConfigValue(profile)));
        }
    }

    @Test
    void packFailureSummariesAreUsefulAndSingleLine() {
        assertEquals("IOException: invalid asset path", FettleExtension.failureDescription(
                new java.io.IOException("invalid\nasset" + (char) 0 + "path")));
        assertEquals("IOException", FettleExtension.failureDescription(new java.io.IOException()));
    }

    private static Subscribe subscribe(String methodName, Class<?> eventType) throws Exception {
        return FettleExtension.class.getMethod(methodName, eventType).getAnnotation(Subscribe.class);
    }
}
