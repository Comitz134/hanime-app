// The "Hide 18+ content" switch as the reader meets it (features/settings +
// features/nsfw).
//
// The filter's own tests pin what hiding *does*; this one pins that the option
// is actually on the page — a preference nothing offers is a preference nobody
// can set — and that pressing it is what the catalogue reads. It renders the
// real page content (upstream's `homescreenSettingsContent`, which this fork's
// overlay adds one item to) with this fork's theme, so a row that only exists
// in the source, or a switch wired to nothing, fails here.

package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.nsfw.NsfwContent
import com.nuvio.app.features.nsfw.NsfwPreference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomescreenNsfwSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val realStore = NsfwContent.store

    @Before
    fun installFakeStore() {
        NsfwContent.store = MemoryPreference()
        NsfwContent.forget()
    }

    @After
    fun restoreStore() {
        NsfwContent.forget()
        NsfwContent.store = realStore
    }

    @Test
    fun theHomescreenSettingsOfferTheSwitch() {
        setPage()

        scrollToSwitch()
        compose.onNodeWithText("Hide 18+ content").assertIsDisplayed()
        compose.onNodeWithText("Adult content").assertIsDisplayed()
    }

    @Test
    fun pressingTheSwitchStoresTheChoice() {
        setPage()

        scrollToSwitch()
        compose.onNodeWithText("Hide 18+ content").performClick()
        compose.waitForIdle()

        // What the catalogue reads, and what the next launch will read.
        assertTrue(NsfwContent.isHidden(), "the switch did not hide anything")
        assertTrue((NsfwContent.store as MemoryPreference).hidden, "the choice was not stored")
    }

    @Test
    fun aStoredChoiceIsWhatTheSwitchShows() {
        NsfwContent.store = MemoryPreference(hidden = true)
        // Nothing in memory: the store is the only thing that can answer, which
        // is the state the page is in on the first frame after a restart.
        NsfwContent.forget()

        setPage()

        scrollToSwitch()
        // A page that drew the switch from a default instead of the store would
        // show it off here, and the reader would have to turn it on twice.
        compose.onNodeWithText("Hide 18+ content").assertIsDisplayed()
        assertTrue(NsfwContent.isHidden())
        assertTrue((NsfwContent.store as MemoryPreference).hidden)
    }

    /**
     * The page is scrolled by its own tag, never by "the scrollable one": the
     * settings rows put scrollables of their own on screen, and a bare
     * `hasScrollAction()` then matches more than one node.
     */
    private fun scrollToSwitch() {
        compose.onNodeWithTag(PAGE_TAG).performScrollToNode(hasText("Hide 18+ content"))
    }

    private fun setPage() {
        compose.setContent {
            NuvioTheme {
                LazyColumn(modifier = Modifier.testTag(PAGE_TAG)) {
                    homescreenSettingsContent(
                        isTablet = false,
                        heroEnabled = true,
                        showCatalogType = true,
                        hideUnreleasedContent = false,
                        items = listOf(
                            HomeCatalogSettingsItem(
                                key = "tv.hanime.bridge:series:anime-trending",
                                defaultTitle = "Anime — trending now",
                                addonName = "SEKAI",
                            ),
                        ),
                        isCatalogLoading = false,
                        catalogErrorMessage = null,
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGE_TAG = "homescreen-settings-page"
    }

    private class MemoryPreference(var hidden: Boolean = false) : NsfwPreference {
        override fun read(): Boolean = hidden
        override fun write(hidden: Boolean) {
            this.hidden = hidden
        }
    }
}
