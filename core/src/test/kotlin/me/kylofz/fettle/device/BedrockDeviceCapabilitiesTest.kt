package me.kylofz.fettle.device

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BedrockDeviceCapabilitiesTest {
    @Test
    fun readsValidatedTopLevelClientCapabilities() {
        val capabilities = BedrockDeviceCapabilities.fromGeyserClientDataJwt(
            jwt(
                """{"DeviceOS":7.0,"DeviceModel":"Phone \"Pro\"","GameVersion":"1.26.10","MemoryTier":1.0,"MaxViewDistance":76.0,"GraphicsMode":2,"CompatibleWithClientSideChunkGen":true,"extension":{"MemoryTier":4}}"""
            ),
            "ANDROID",
        )

        assertEquals("ANDROID", capabilities.platform)
        assertEquals(7, capabilities.deviceOs)
        assertEquals("Phone \"Pro\"", capabilities.deviceModel)
        assertEquals("1.26.10", capabilities.gameVersion)
        assertEquals(1, capabilities.memoryTier)
        assertEquals(76, capabilities.maxViewDistance)
        assertEquals(2, capabilities.graphicsMode)
        assertEquals(true, capabilities.compatibleWithClientSideChunkGen)
    }

    @Test
    fun rejectsMalformedJwtAndOutOfRangeOrFractionalClaims() {
        val malformed = BedrockDeviceCapabilities.fromGeyserClientDataJwt("not-a-jwt", "WINDOWS")
        assertEquals("WINDOWS", malformed.platform)
        assertNull(malformed.memoryTier)

        val capabilities = BedrockDeviceCapabilities.fromGeyserClientDataJwt(
            jwt("""{"DeviceOS":0,"MemoryTier":4.5,"MaxViewDistance":0,"GraphicsMode":99,"CompatibleWithClientSideChunkGen":"true"}"""),
            null,
        )
        assertNull(capabilities.deviceOs)
        assertNull(capabilities.memoryTier)
        assertNull(capabilities.maxViewDistance)
        assertNull(capabilities.graphicsMode)
        assertNull(capabilities.compatibleWithClientSideChunkGen)
    }

    @Test
    fun rejectsDuplicateCapabilityClaimsInsteadOfPickingOne() {
        val capabilities = BedrockDeviceCapabilities.fromGeyserClientDataJwt(
            jwt("""{"MemoryTier":1,"MemoryTier":4}"""),
            "ANDROID",
        )

        assertEquals("ANDROID", capabilities.platform)
        assertNull(capabilities.memoryTier)
    }

    @Test
    fun describesMemoryTierAsAReportedBandNotAnExactRamReading() {
        val descriptions = (0..4).map { tier ->
            BedrockDeviceCapabilities.fromGeyserClientDataJwt(jwt("""{"MemoryTier":$tier}"""), null)
                .memoryTierDescription
        }

        assertEquals(
            listOf(
                "SuperLow (up to 1.5 GB)",
                "Low (up to 2 GB)",
                "Mid (up to 4 GB)",
                "High (up to 8 GB)",
                "SuperHigh (over 8 GB)",
            ),
            descriptions,
        )
        assertEquals("unknown", BedrockDeviceCapabilities.fromGeyserClientDataJwt(null, null).memoryTierDescription)
    }

    @Test
    fun usesValidatedValuesGeyserAlreadyParsedWithoutInventingMissingSpecs() {
        val capabilities = BedrockDeviceCapabilities.fromGeyserClientDataJwt(null, "ANDROID")
            .withGeyserParsedIdentity(7, "Pixel 8", "1.26.10")

        assertEquals(7, capabilities.deviceOs)
        assertEquals("Pixel 8", capabilities.deviceModel)
        assertEquals("1.26.10", capabilities.gameVersion)
        assertNull(capabilities.memoryTier)
    }

    private fun jwt(payload: String): String = "header." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8)) +
        ".signature"
}
