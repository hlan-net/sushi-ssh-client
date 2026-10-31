package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GeminiClient` used to hard-code `gemini-2.5-pro` / `gemini-1.5-flash`; the latter stopped
 * existing entirely (`docs/process/DEPENDENCY_LIFECYCLE.md` §1). The fix resolves a capability to
 * whatever id the API's model list currently has, so the acceptance test for that is exactly
 * this: rename every id in the fixture and confirm resolution still finds something reasonable.
 */
class GeminiModelResolverTest {

    private val currentGenerationModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-3-flash-preview",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "text-embedding-004"
    )

    @Test
    fun capable_picksTheHighestVersionEvenWhenItIsOnlyAPreview() {
        // gemini-2.5-pro is stable but the older generation; gemini-3.1-pro-preview is newer and
        // wins despite being a preview — "preview" isn't a synonym for "worse" in this API.
        val resolved = GeminiModelResolver.resolve(GeminiModelCapability.CAPABLE, currentGenerationModels)

        assertEquals("gemini-3.1-pro-preview", resolved)
    }

    @Test
    fun fast_prefersTheHighestVersionedStableFlashModel() {
        val resolved = GeminiModelResolver.resolve(GeminiModelCapability.FAST, currentGenerationModels)

        assertEquals("gemini-3.8-flash", resolved)
    }

    @Test
    fun versionsCompareByComponent_so3Point10OutranksThreePoint9() {
        // Parsed as a Double, "3.10" would read as 3.1 and lose to 3.9.
        val resolved = GeminiModelResolver.resolve(
            GeminiModelCapability.FAST,
            listOf("gemini-3.9-flash", "gemini-3.10-flash", "gemini-3.2-flash")
        )

        assertEquals("gemini-3.10-flash", resolved)
    }

    @Test
    fun aVersionWithoutAMinorComponentEqualsItsDotZero() {
        // "gemini-4" and "gemini-4.0" are the same version, so the stable one wins the tie.
        val resolved = GeminiModelResolver.resolve(
            GeminiModelCapability.CAPABLE,
            listOf("gemini-4.0-pro-preview", "gemini-4-pro")
        )

        assertEquals("gemini-4-pro", resolved)
    }

    @Test
    fun credentialId_differsPerApiKeyAndPerAccount_andNeverHoldsTheKey() {
        val keyA = GeminiModelCatalog.credentialId(accountEmail = null, apiKey = "AIza-key-a")
        val keyB = GeminiModelCatalog.credentialId(accountEmail = null, apiKey = "AIza-key-b")
        val account = GeminiModelCatalog.credentialId(accountEmail = "larry@example.com", apiKey = "AIza-key-a")

        assertNotEquals(keyA, keyB)
        assertNotEquals(keyA, account)
        assertEquals(keyA, GeminiModelCatalog.credentialId(accountEmail = null, apiKey = "AIza-key-a"))
        assertFalse("AIza-key-a" in keyA)
    }

    @Test
    fun capable_prefersStableOverPreviewOnlyAtTheSameVersion() {
        val sameVersion = listOf("gemini-4.0-pro-preview", "gemini-4.0-pro")

        val resolved = GeminiModelResolver.resolve(GeminiModelCapability.CAPABLE, sameVersion)

        assertEquals("gemini-4.0-pro", resolved)
    }

    @Test
    fun fast_excludesFlashLiteAndOtherSpecialisedVariants() {
        val onlyLiteAndImage = listOf("gemini-9.0-flash-lite", "gemini-9.0-flash-image", "gemini-9.0-flash-tts")

        val resolved = GeminiModelResolver.resolve(GeminiModelCapability.FAST, onlyLiteAndImage)

        // None of the fixture ids are eligible, so this must fall back rather than pick a
        // specialised model masquerading as the chat one.
        assertTrue("flash" in resolved && "lite" !in resolved && "image" !in resolved && "tts" !in resolved)
    }

    @Test
    fun aWholesaleModelLineRename_stillResolvesToSomethingReasonable() {
        // The same shapes, renamed to a fictitious future generation — nothing in this app's
        // code should need to change for that.
        val renamed = listOf(
            "sushi-9.9-flash",
            "sushi-9.9-flash-lite",
            "sushi-9.9-pro-preview",
            "sushi-9.1-pro"
        )

        val fast = GeminiModelResolver.resolve(GeminiModelCapability.FAST, renamed)
        val capable = GeminiModelResolver.resolve(GeminiModelCapability.CAPABLE, renamed)

        assertEquals("sushi-9.9-flash", fast)
        // Prefers the stable (non-preview) "pro" match even though it's an older version number.
        assertEquals("sushi-9.1-pro", capable)
    }

    @Test
    fun emptyList_fallsBackRatherThanCrashing() {
        assertEquals("gemini-3.1-pro-preview", GeminiModelResolver.resolve(GeminiModelCapability.CAPABLE, emptyList()))
        assertEquals("gemini-3.5-flash", GeminiModelResolver.resolve(GeminiModelCapability.FAST, emptyList()))
    }

    @Test
    fun capability_fromStorageToken() {
        assertEquals(GeminiModelCapability.FAST, GeminiModelCapability.from("fast"))
        assertEquals(GeminiModelCapability.CAPABLE, GeminiModelCapability.from("capable"))
        assertEquals(GeminiModelCapability.CAPABLE, GeminiModelCapability.from(null))
    }

    @Test
    fun capability_fromALegacyRawModelId_carriesThePreviousChoiceOver() {
        assertEquals(GeminiModelCapability.FAST, GeminiModelCapability.from("gemini-1.5-flash"))
        assertEquals(GeminiModelCapability.CAPABLE, GeminiModelCapability.from("gemini-2.5-pro"))
    }
}
