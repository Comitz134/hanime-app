package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an account pull does to the addon list.
 *
 * The bug this exists for: signing up creates an account with no addon rows,
 * the pull treated that as "the reader has no addons", and the window was left
 * with an empty catalogue — search said *No active addons* and nothing played.
 * The app is built around one addon of its own, so an account that has never
 * held a row keeps the device's list instead.
 */
class AddonPullSeedTest {

    private val builtIn = "http://127.0.0.1:8787/addon/manifest.json"
    private val other = "https://addon.example.test/manifest.json"

    @Test
    fun aFreshAccountKeepsTheAppsOwnAddon() {
        assertEquals(listOf(builtIn), addonUrlsAfterPull(accountUrls = emptyList(), localUrls = listOf(builtIn)))
    }

    @Test
    fun aFreshAccountKeepsEveryLocalAddonInOrder() {
        assertEquals(
            listOf(builtIn, other),
            addonUrlsAfterPull(accountUrls = emptyList(), localUrls = listOf(builtIn, other)),
        )
    }

    @Test
    fun theAccountsListIsAuthoritativeWhenItHasRows() {
        // Deleting the built-in on another client has to stay deleted here —
        // only an account with *no* rows falls back, never a partial list.
        assertEquals(listOf(other), addonUrlsAfterPull(accountUrls = listOf(other), localUrls = listOf(builtIn)))
    }

    @Test
    fun theAccountsOrderIsKept() {
        assertEquals(
            listOf(other, builtIn),
            addonUrlsAfterPull(accountUrls = listOf(other, builtIn), localUrls = listOf(builtIn)),
        )
    }

    @Test
    fun nothingAnywhereTimesNothingStaysNothing() {
        // No inventing an addon: a device with nothing local stays with nothing,
        // so bringing a new account up cannot conjure a URL that was never seeded.
        assertEquals(emptyList(), addonUrlsAfterPull(accountUrls = emptyList(), localUrls = emptyList()))
    }

    @Test
    fun aLocalUrlWithoutTheManifestSuffixIsNormalisedLikeEverywhereElse() {
        assertEquals(
            listOf(builtIn),
            addonUrlsAfterPull(accountUrls = emptyList(), localUrls = listOf("http://127.0.0.1:8787/addon")),
        )
    }
}
