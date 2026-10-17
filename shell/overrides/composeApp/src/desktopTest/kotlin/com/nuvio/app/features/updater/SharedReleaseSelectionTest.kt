package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which release a check settles on, over the list this repository really
 * produces.
 *
 * Two clients are published from one repository: the phone app answers its own
 * update check from `releases/latest` and its releases carry no asset the window
 * can install. A check that only ever considered the newest release would
 * therefore report "nothing new" for as long as a phone release happened to be
 * the most recent thing published — which, with two clients released from one
 * repository, is most of the time. The release list below is that shape on
 * purpose, with the phone's release newest.
 */
class SharedReleaseSelectionTest {

    private val desktopSelector = AppUpdaterPlatform.assetSelector

    private fun release(
        tag: String,
        prerelease: Boolean,
        vararg assets: Pair<String, String>,
    ): String {
        val assetJson = assets.joinToString(",") { (name, contentType) ->
            """
            {"name":"$name","browser_download_url":"https://example.invalid/$name",
             "size":1234,"content_type":"$contentType"}
            """.trimIndent()
        }
        return """
            {"tag_name":"$tag","name":"$tag","body":"notes for $tag","draft":false,
             "prerelease":$prerelease,"html_url":"https://example.invalid/$tag",
             "assets":[$assetJson]}
        """.trimIndent()
    }

    private fun list(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test
    fun `the desktop release is found behind a newer phone release`() {
        val body = list(
            release("v9.9.9", prerelease = false, "sekai-9.9.9.apk" to "application/vnd.android.package-archive"),
            release("v1.0.1", prerelease = true, "SEKAI-Windows-x64-1.0.1.zip" to "application/zip"),
        )

        val update = AppUpdaterRepository.selectUpdate(
            responseBody = body,
            channel = UpdateChannel.ALL_RELEASES,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            selector = desktopSelector,
        )

        assertEquals("v1.0.1", update?.tag)
        assertEquals("SEKAI-Windows-x64-1.0.1.zip", update?.assetName)
        assertEquals(1234L, update?.assetSizeBytes)
    }

    @Test
    fun `the newest desktop release wins over an older one`() {
        val body = list(
            release("v1.0.2", prerelease = true, "SEKAI-Windows-x64-1.0.2.zip" to "application/zip"),
            release("v1.0.1", prerelease = true, "SEKAI-Windows-x64-1.0.1.zip" to "application/zip"),
        )

        val update = AppUpdaterRepository.selectUpdate(
            responseBody = body,
            channel = UpdateChannel.ALL_RELEASES,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            selector = desktopSelector,
        )

        assertEquals("v1.0.2", update?.tag)
    }

    @Test
    fun `a list with nothing the window can install offers no update`() {
        val body = list(
            release("v9.9.9", prerelease = false, "sekai-9.9.9.apk" to "application/vnd.android.package-archive"),
            release("channel-beta", prerelease = true, "version.json" to "application/json"),
        )

        assertNull(
            AppUpdaterRepository.selectUpdate(
                responseBody = body,
                channel = UpdateChannel.ALL_RELEASES,
                supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
                selector = desktopSelector,
            )
        )
    }

    @Test
    fun `the windows asset is the portable zip, not the phone's manifest`() {
        assertEquals(listOf(".zip"), desktopSelector.fileExtensions)
        assertEquals(listOf("application/zip"), desktopSelector.contentTypes)
    }

    @Test
    fun `the release source is this project's, not upstream's`() {
        assertEquals("Comitz134", AppUpdaterPlatform.releaseSource.owner)
        assertEquals("hanime-app", AppUpdaterPlatform.releaseSource.repo)
        // Prereleases have to be visible: the desktop releases are published as
        // prereleases so the phone's `releases/latest` does not move to them.
        assertEquals(true, AppUpdaterPlatform.releaseSource.includePrereleases)
    }
}
