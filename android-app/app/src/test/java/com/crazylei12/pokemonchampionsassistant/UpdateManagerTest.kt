package com.crazylei12.pokemonchampionsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun versionComparisonSupportsPrefixAndBuildMetadata() {
        assertTrue(ReleaseVersion.isNewer("v0.2.1+build.7", "0.2.0"))
        assertEquals(0, ReleaseVersion.compare("v1.2.3+one", "1.2.3+two"))
    }

    @Test
    fun stableVersionSortsAfterPrerelease() {
        assertTrue(ReleaseVersion.isNewer("1.0.0", "1.0.0-rc.2"))
        assertFalse(ReleaseVersion.isNewer("1.0.0-beta.2", "1.0.0-beta.11"))
    }

    @Test
    fun stableChannelIgnoresPrereleasesAndDrafts() {
        val result = ReleaseSelector.newest(
            listOf(
                release("v0.3.0-beta.1", prerelease = true),
                release("v0.2.1"),
                release("v9.0.0", draft = true),
            ),
            UpdateChannel.STABLE,
        )

        assertEquals("v0.2.1", result?.tagName)
    }

    @Test
    fun previewChannelChoosesHighestSemanticVersion() {
        val result = ReleaseSelector.newest(
            listOf(
                release("v0.3.0-beta.1", prerelease = true),
                release("v0.2.5"),
                release("not-a-version"),
            ),
            UpdateChannel.PREVIEW,
        )

        assertEquals("v0.3.0-beta.1", result?.tagName)
    }

    private fun release(
        tagName: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = ReleaseInfo(
        tagName = tagName,
        title = tagName,
        notes = "",
        pageUrl = "https://example.invalid/$tagName",
        apkUrl = null,
        prerelease = prerelease,
        draft = draft,
    )
}
