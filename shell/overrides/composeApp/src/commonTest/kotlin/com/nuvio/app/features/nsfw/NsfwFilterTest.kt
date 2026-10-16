// The reader's "hide 18+" answer, and the one seam it is applied in
// (features/nsfw + features/addons).
//
// The claim these tests pin is the one the option makes: a hidden shelf is
// hidden *everywhere the catalogue is read from*, because every reader — home
// rails, search, discover, catalog pages, collections — asks `enabledAddons()`
// for its addons. They pin what the option does not do as well: nothing is
// deleted, the addon itself stays (so a title the reader already saved still
// opens, plays and sits in their library), and the shelves it does not name are
// untouched.
//
// Each test is written so that dropping the filter fails it — the sink is
// `enabledAddons()`, and the shelf ids that come out are the assertion.

package com.nuvio.app.features.nsfw

import com.nuvio.app.features.addons.AddonBehaviorHints
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NsfwFilterTest {

    private val realStore = NsfwContent.store

    @BeforeTest
    fun installFakeStore() {
        // The store the app writes to is a real file on the machine running
        // this: a fake keeps a test run out of it, and lets a saved answer be
        // asserted rather than assumed.
        NsfwContent.store = MemoryPreference()
        NsfwContent.forget()
    }

    @AfterTest
    fun restoreStore() {
        NsfwContent.forget()
        NsfwContent.store = realStore
    }

    @Test
    fun `the 18+ shelves are dropped while the option is on`() {
        NsfwContent.setHidden(true)

        val addon = listOf(sekai()).enabledAddons().single()

        assertEquals(listOf("anime-trending", "films-listing"), shelfIds(addon))
        // The addon itself stays whole: its meta, streams and subtitles are
        // how a title the reader already saved still opens and plays.
        assertEquals("tv.hanime.bridge", addon.manifest?.id)
        assertEquals(listOf("movie", "series", "hentai"), addon.manifest?.types)
    }

    @Test
    fun `the option off leaves every shelf where it was`() {
        NsfwContent.setHidden(false)

        assertEquals(
            listOf("anime-trending", "films-listing", "adult-newest"),
            shelfIds(listOf(sekai()).enabledAddons().single()),
        )
    }

    @Test
    fun `a choice made last time is read back this time`() {
        NsfwContent.store = MemoryPreference(hidden = true)
        // Nothing in memory: the store is the only thing that can answer, which
        // is the state the app is in on the first frame after a restart.
        NsfwContent.forget()

        assertTrue(NsfwContent.isHidden())
        assertEquals(listOf("anime-trending", "films-listing"), shelfIds(listOf(sekai()).enabledAddons().single()))
    }

    @Test
    fun `the switch's answer is written down`() {
        NsfwContent.setHidden(true)
        assertTrue((NsfwContent.store as MemoryPreference).hidden, "the choice was not stored")

        NsfwContent.setHidden(false)
        assertFalse((NsfwContent.store as MemoryPreference).hidden)
    }

    @Test
    fun `a source that declares itself adult offers nothing while hidden`() {
        NsfwContent.setHidden(true)

        val addon = listOf(
            sekai(behaviorHints = AddonBehaviorHints(adult = true)),
        ).enabledAddons().single()

        assertEquals(emptyList(), shelfIds(addon))
    }

    @Test
    fun `hiding a shelf does not delete it`() {
        NsfwContent.setHidden(true)

        val installed = sekai()
        val offered = listOf(installed).enabledAddons().single()

        // What is installed is untouched — the filter answers a reader, it does
        // not edit the addon — so turning the option off restores the shelf
        // exactly as it was.
        assertEquals(3, installed.manifest?.catalogs?.size)
        assertEquals(2, offered.manifest?.catalogs?.size)
        assertFalse(installed === offered, "the addon was filtered in place")
    }

    // --------------------------------------------------------------- fixtures

    /** The catalogue this window ships with: two mainstream shelves, one 18+. */
    private fun sekai(behaviorHints: AddonBehaviorHints = AddonBehaviorHints()): ManagedAddon =
        ManagedAddon(
            manifestUrl = "http://127.0.0.1:8787/addon/manifest.json",
            manifest = AddonManifest(
                id = "tv.hanime.bridge",
                name = "SEKAI",
                description = "Anime, films and series from this server, plus its own 18+ shelf.",
                version = "1.0.0",
                resources = listOf(
                    AddonResource(
                        name = "catalog",
                        types = listOf("movie", "series", "hentai"),
                    ),
                ),
                types = listOf("movie", "series", "hentai"),
                catalogs = listOf(
                    AddonCatalog(type = "series", id = "anime-trending", name = "Anime — trending now"),
                    AddonCatalog(type = "movie", id = "films-listing", name = "Films — popular now"),
                    AddonCatalog(type = "hentai", id = "adult-newest", name = "Hentai — newest"),
                ),
                behaviorHints = behaviorHints,
                transportUrl = "http://127.0.0.1:8787/addon",
            ),
        )

    private fun shelfIds(addon: ManagedAddon): List<String> =
        addon.manifest?.catalogs.orEmpty().map { catalog -> catalog.id }

    private class MemoryPreference(var hidden: Boolean = false) : NsfwPreference {
        override fun read(): Boolean = hidden
        override fun write(hidden: Boolean) {
            this.hidden = hidden
        }
    }
}
